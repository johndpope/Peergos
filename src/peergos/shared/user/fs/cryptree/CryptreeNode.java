package peergos.shared.user.fs.cryptree;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** A cryptree node controls read and write access to a directory or file.
 *
 * A directory contains the following distinct symmetric read keys {base, parent}, and file contains {base == parent, data}
 * A directory or file also has a single base symmetric write key
 *
 * The serialized encrypted form stores a link from the base key to the other key.
 * For a directory, the base key encrypts the links to child directories and files. For a file the datakey encrypts the
 * file's data. The parent key encrypts the link to the parent directory's parent key and the metadata (FileProperties).
 *
 * There are three network visible components to the serialization:
 * 1) A fixed size block encrypted with the base key, containing the second key (parent or data key), the location of
 *       the next chunk, and an optional symmetric link to a signing pair
 * 2) A fragmented padded cipher text, padded to a multiple of 4096,
 *       containing the relative child links of a directory, or the data of a file chunk
 * 3) A padded cipher text (to a multiple of 16 bytes) of an optional relative parent link, and file properties
 *       The parent link is present on the first chunk of all files and directories except your home directory
 */
public class CryptreeNode implements Cborable {
    private static final int CURRENT_VERSION = 1;
    private static final int META_DATA_PADDING_BLOCKSIZE = 16;
    private static final int BASE_BLOCK_PADDING_BLOCKSIZE = 64;
    private static final int MIN_FRAGMENT_SIZE = 4096;
    private static int MAX_CHILD_LINKS_PER_BLOB = 500;

    public static synchronized void setMaxChildLinkPerBlob(int newValue) {
        MAX_CHILD_LINKS_PER_BLOB = newValue;
    }

    public static synchronized int getMaxChildLinksPerBlob() {
        return MAX_CHILD_LINKS_PER_BLOB;
    }

    private transient final MaybeMultihash lastCommittedHash;
    private transient final boolean isDirectory;
    private final PaddedCipherText fromBaseKey;
    private final FragmentedPaddedCipherText childrenOrData;
    private final PaddedCipherText fromParentKey;

    public CryptreeNode(MaybeMultihash lastCommittedHash,
                        boolean isDirectory,
                        PaddedCipherText fromBaseKey,
                        FragmentedPaddedCipherText childrenOrData,
                        PaddedCipherText fromParentKey) {
        this.lastCommittedHash = lastCommittedHash;
        this.isDirectory = isDirectory;
        this.fromBaseKey = fromBaseKey;
        this.childrenOrData = childrenOrData;
        this.fromParentKey = fromParentKey;
    }

    public int getVersion() {
        return CURRENT_VERSION;
    }

    private static class FromBase implements Cborable {
        public final SymmetricKey parentOrData;
        public final Optional<SymmetricLinkToSigner> signer;
        public final RelativeCapability nextChunk;

        public FromBase(SymmetricKey parentOrData,
                        Optional<SymmetricLinkToSigner> signer,
                        RelativeCapability nextChunk) {
            this.parentOrData = parentOrData;
            this.signer = signer;
            this.nextChunk = nextChunk;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("k", parentOrData);
            signer.ifPresent(w -> state.put("w", w));
            state.put("n", nextChunk);
            return CborObject.CborMap.build(state);
        }

        public static FromBase fromCbor(CborObject cbor) {
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Incorrect cbor for FromBase: " + cbor);

            CborObject.CborMap m = (CborObject.CborMap) cbor;
            SymmetricKey k = m.get("k", SymmetricKey::fromCbor);
            Optional<SymmetricLinkToSigner> w = m.getOptional("w", SymmetricLinkToSigner::fromCbor);
            RelativeCapability nextChunk = m.get("n", RelativeCapability::fromCbor);
            return new FromBase(k, w, nextChunk);
        }
    }

    private FromBase getBaseBlock(SymmetricKey baseKey) {
        return fromBaseKey.decrypt(baseKey, FromBase::fromCbor);
    }

    private static class FromParent implements Cborable {
        public final Optional<RelativeCapability> parentLink;
        public final FileProperties properties;

        public FromParent(Optional<RelativeCapability> parentLink, FileProperties properties) {
            this.parentLink = parentLink;
            this.properties = properties;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            parentLink.ifPresent(p -> state.put("p", p));
            state.put("s", properties);
            return CborObject.CborMap.build(state);
        }

        public static FromParent fromCbor(CborObject cbor) {
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Incorrect cbor for FromParent: " + cbor);

            CborObject.CborMap m = (CborObject.CborMap) cbor;
            Optional<RelativeCapability> parentLink = m.getOptional("p", RelativeCapability::fromCbor);
            FileProperties properties = m.get("s", FileProperties::fromCbor);
            return new FromParent(parentLink, properties);
        }
    }

    private FromParent getParentBlock(SymmetricKey parentKey) {
        return fromParentKey.decrypt(parentKey, FromParent::fromCbor);
    }

    public static class DirAndChildren {
        public final CryptreeNode dir;
        public final List<FragmentWithHash> childData;

        public DirAndChildren(CryptreeNode dir, List<FragmentWithHash> childData) {
            this.dir = dir;
            this.childData = childData;
        }

        public CompletableFuture<Snapshot> commit(Snapshot current,
                                                  Committer committer,
                                                  WritableAbsoluteCapability us,
                                                  Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                  NetworkAccess network,
                                                  TransactionId tid) {
            SigningPrivateKeyAndPublicHash signer = dir.getSigner(us.rBaseKey, us.wBaseKey.get(), entryWriter);
            return commit(current, committer, us, signer, network, tid);
        }

        public CompletableFuture<Snapshot> commit(Snapshot current,
                                                  Committer committer,
                                                  WritableAbsoluteCapability us,
                                                  SigningPrivateKeyAndPublicHash signer,
                                                  NetworkAccess network,
                                                  TransactionId tid) {
            return commitChildrenLinks(us, signer, network, tid)
                    .thenCompose(hashes -> dir.commit(current, committer, us, signer, network, tid));
        }

        public CompletableFuture<List<Multihash>> commitChildrenLinks(WritableAbsoluteCapability us,
                                                                      Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                                      NetworkAccess network,
                                                                      TransactionId tid) {
            SigningPrivateKeyAndPublicHash signer = dir.getSigner(us.rBaseKey, us.wBaseKey.get(), entryWriter);
            return commitChildrenLinks(us, signer, network, tid);
        }

        public CompletableFuture<List<Multihash>> commitChildrenLinks(WritableAbsoluteCapability us,
                                                                      SigningPrivateKeyAndPublicHash signer,
                                                                      NetworkAccess network,
                                                                      TransactionId tid) {
            List<Fragment> frags = childData.stream()
                    .filter(f -> ! f.hash.isIdentity())
                    .map(f -> f.fragment)
                    .collect(Collectors.toList());
            return network.uploadFragments(frags, us.owner, signer, l -> {}, tid);
        }
    }

    public static class ChildrenLinks implements Cborable {
        public final List<RelativeCapability> children;

        public ChildrenLinks(List<RelativeCapability> children) {
            this.children = children;
        }

        @Override
        public CborObject toCbor() {
            return new CborObject.CborList(children);
        }

        public static ChildrenLinks fromCbor(CborObject cbor) {
            if (! (cbor instanceof CborObject.CborList))
                throw new IllegalStateException("Incorrect cbor for ChildrenLinks: " + cbor);

            return new ChildrenLinks(((CborObject.CborList) cbor).value
                    .stream()
                    .map(RelativeCapability::fromCbor)
                    .collect(Collectors.toList()));
        }

        public static ChildrenLinks empty() {
            return new ChildrenLinks(Collections.emptyList());
        }
    }

    public MaybeMultihash committedHash() {
        return lastCommittedHash;
    }

    public boolean isDirectory() {
        return isDirectory;
    }

    public boolean isReadable(SymmetricKey baseKey) {
        try {
            getBaseBlock(baseKey);
            return true;
        } catch (Exception e) {}
        return false;
    }

    public Optional<SymmetricLinkToSigner> getWriterLink(SymmetricKey baseKey) {
        return getBaseBlock(baseKey).signer;
    }

    public SymmetricKey getParentKey(SymmetricKey baseKey) {
        if (isDirectory())
            try {
                return getBaseBlock(baseKey).parentOrData;
            } catch (Exception e) {
                // if we don't have read access to this folder, then we must just have the parent key already
            }
        return baseKey;
    }

    public SymmetricKey getDataKey(SymmetricKey baseKey) {
        if (isDirectory())
            throw new IllegalStateException("Directories don't have a data key!");
        return getBaseBlock(baseKey).parentOrData;
    }

    public FileProperties getProperties(SymmetricKey parentKey) {
        return getParentBlock(parentKey).properties;
    }

    public SigningPrivateKeyAndPublicHash getSigner(SymmetricKey rBaseKey,
                                                    SymmetricKey wBaseKey,
                                                    Optional<SigningPrivateKeyAndPublicHash> entrySigner) {
        return getBaseBlock(rBaseKey).signer
                .map(link -> link.target(wBaseKey))
                .orElseGet(() -> entrySigner.orElseThrow(() ->
                        new IllegalStateException("No link to private signing key present on directory!")));
    }

    public FileRetriever retriever(SymmetricKey baseKey, Optional<byte[]> streamSecret, byte[] currentMapKey, Hasher hasher) {
        byte[] nextChunkLocation = getNextChunkLocation(baseKey, streamSecret, currentMapKey, hasher);
        return new EncryptedChunkRetriever(childrenOrData, nextChunkLocation, getDataKey(baseKey));
    }

    public CompletableFuture<List<RelativeCapability>> getDirectChildren(SymmetricKey baseKey, NetworkAccess network) {
        if (! isDirectory)
            return CompletableFuture.completedFuture(Collections.emptyList());
        return getLinkedData(baseKey, ChildrenLinks::fromCbor, network, x -> {})
                .thenApply(c -> c.children);
    }

    public CompletableFuture<Set<AbsoluteCapability>> getDirectChildrenCapabilities(AbsoluteCapability us, NetworkAccess network) {
        return getDirectChildren(us.rBaseKey, network)
                .thenApply(c ->c.stream()
                        .map(cap -> cap.toAbsolute(us))
                        .collect(Collectors.toSet()));
    }

    public CompletableFuture<Set<AbsoluteCapability>> getAllChildrenCapabilities(Snapshot version,
                                                                                 AbsoluteCapability us,
                                                                                 Hasher hasher,
                                                                                 NetworkAccess network) {
        CompletableFuture<Set<AbsoluteCapability>> childrenFuture = getDirectChildrenCapabilities(us, network);

        CompletableFuture<Optional<RetrievedCapability>> moreChildrenFuture = getNextChunk(version, us, network,
                Optional.empty(), hasher);

        return childrenFuture.thenCompose(children -> moreChildrenFuture.thenCompose(moreChildrenSource -> {
                    CompletableFuture<Set<AbsoluteCapability>> moreChildren = moreChildrenSource
                            .map(d -> d.fileAccess.getAllChildrenCapabilities(version, d.capability, hasher, network))
                            .orElse(CompletableFuture.completedFuture(Collections.emptySet()));
                    return moreChildren.thenApply(moreRetrievedChildren -> {
                        Set<AbsoluteCapability> results = Stream.concat(
                                children.stream(),
                                moreRetrievedChildren.stream())
                                .collect(Collectors.toSet());
                        return results;
                    });
                })
        );
    }

    public CompletableFuture<Set<RetrievedCapability>> getDirectChildren(NetworkAccess network,
                                                                         AbsoluteCapability us,
                                                                         Snapshot version) {
        return getDirectChildrenCapabilities(us, network)
                .thenCompose(c -> network.retrieveAllMetadata(c.stream()
                        .collect(Collectors.toList()), version)
                        .thenApply(HashSet::new));
    }

    public CompletableFuture<Set<RetrievedCapability>> getChildren(Snapshot version,
                                                                   Hasher hasher,
                                                                   NetworkAccess network,
                                                                   AbsoluteCapability us) {
        CompletableFuture<Set<RetrievedCapability>> childrenFuture = getDirectChildren(network, us, version);

        CompletableFuture<Optional<RetrievedCapability>> moreChildrenFuture = getNextChunk(version, us, network,
                Optional.empty(), hasher);

        return childrenFuture.thenCompose(children -> moreChildrenFuture.thenCompose(moreChildrenSource -> {
                    CompletableFuture<Set<RetrievedCapability>> moreChildren = moreChildrenSource
                            .map(d -> d.fileAccess.getChildren(version, hasher, network, d.capability))
                            .orElse(CompletableFuture.completedFuture(Collections.emptySet()));
                    return moreChildren.thenApply(moreRetrievedChildren -> {
                        Set<RetrievedCapability> results = Stream.concat(
                                children.stream(),
                                moreRetrievedChildren.stream())
                                .collect(Collectors.toSet());
                        return results;
                    });
                })
        );
    }

    public CompletableFuture<Snapshot> updateProperties(Snapshot base,
                                                        Committer committer,
                                                        WritableAbsoluteCapability us,
                                                        Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                        FileProperties newProps,
                                                        NetworkAccess network) {

        SymmetricKey parentKey = getParentKey(us.rBaseKey);
        FromParent parentBlock = getParentBlock(parentKey);
        FromParent newParentBlock = new FromParent(parentBlock.parentLink, newProps);
        CryptreeNode updated = new CryptreeNode(lastCommittedHash, isDirectory, fromBaseKey, childrenOrData,
                PaddedCipherText.build(parentKey, newParentBlock, META_DATA_PADDING_BLOCKSIZE));
        return IpfsTransaction.call(us.owner,
                tid -> network.uploadChunk(base, committer, updated, us.owner, us.getMapKey(), getSigner(us.rBaseKey, us.wBaseKey.get(), entryWriter), tid),
                network.dhtClient);
    }

    public boolean isDirty(SymmetricKey baseKey) {
        if (isDirectory())
            return false;
        return getBaseBlock(baseKey).parentOrData.isDirty();
    }

    public CryptreeNode withHash(Multihash hash) {
        return new CryptreeNode(MaybeMultihash.of(hash), isDirectory, fromBaseKey, childrenOrData, fromParentKey);
    }

    public CryptreeNode withWriterLink(SymmetricKey baseKey, SymmetricLinkToSigner newWriterLink) {
        return withWriterLink(baseKey, Optional.of(newWriterLink));
    }

    public CryptreeNode withWriterLink(SymmetricKey baseKey, Optional<SymmetricLinkToSigner> newWriterLink) {
        FromBase baseBlock = getBaseBlock(baseKey);
        FromBase newBaseBlock = new FromBase(baseBlock.parentOrData, newWriterLink, baseBlock.nextChunk);
        PaddedCipherText encryptedBaseBlock = PaddedCipherText.build(baseKey, newBaseBlock, BASE_BLOCK_PADDING_BLOCKSIZE);
        return new CryptreeNode(lastCommittedHash, isDirectory, encryptedBaseBlock, childrenOrData, fromParentKey);
    }

    public DirAndChildren withChildren(SymmetricKey baseKey, ChildrenLinks children, Hasher hasher) {
        Pair<FragmentedPaddedCipherText, List<FragmentWithHash>> encryptedChildren = buildChildren(children, baseKey, hasher);
        CryptreeNode cryptreeNode = new CryptreeNode(lastCommittedHash, isDirectory, fromBaseKey, encryptedChildren.left, fromParentKey);
        return new DirAndChildren(cryptreeNode, encryptedChildren.right);
    }

    private static Pair<FragmentedPaddedCipherText, List<FragmentWithHash>> buildChildren(ChildrenLinks children, SymmetricKey rBaseKey, Hasher hasher) {
        return FragmentedPaddedCipherText.build(rBaseKey, children, MIN_FRAGMENT_SIZE, Fragment.MAX_LENGTH, hasher, false);
    }

    public <T> CompletableFuture<T> getLinkedData(SymmetricKey baseOrDataKey,
                                                  Function<CborObject, T> fromCbor,
                                                  NetworkAccess network,
                                                  ProgressConsumer<Long> progress) {
        return childrenOrData.getAndDecrypt(baseOrDataKey, fromCbor, network, progress);
    }

    public CryptreeNode withParentLink(SymmetricKey parentKey, RelativeCapability newParentLink) {
        FromParent parentBlock = getParentBlock(parentKey);
        FromParent newParentBlock = new FromParent(Optional.of(newParentLink), parentBlock.properties);
        return new CryptreeNode(lastCommittedHash, isDirectory, fromBaseKey, childrenOrData,
                PaddedCipherText.build(parentKey, newParentBlock, META_DATA_PADDING_BLOCKSIZE));
    }

    /**
     *
     * @param rBaseKey
     * @return the mapkey of the next chunk of this file or folder if present
     */
    public byte[] getNextChunkLocation(SymmetricKey rBaseKey,
                                       Optional<byte[]> streamSecret,
                                       byte[] currentMapKey,
                                       Hasher hasher) {
        // It is important to use the hash based subsequent chunk location generator if the first chunk is labelled as such
        // Otherwise we are vulnerable to a downgrade attack where a malicious user with a malicious client
        // could upload a file that claimed to be using the new hash based generator, but the sequential links did not
        if (streamSecret.isPresent())
            return FileProperties.calculateNextMapKey(streamSecret.get(), currentMapKey, hasher);
        // This is only here to support legacy files uploaded before hash based seeking was implemented
        return getBaseBlock(rBaseKey).nextChunk.getMapKey();
    }

    public CompletableFuture<Optional<RetrievedCapability>> getNextChunk(Snapshot version,
                                                                         AbsoluteCapability us,
                                                                         NetworkAccess network,
                                                                         Optional<byte[]> streamSecret,
                                                                         Hasher hasher) {
        AbsoluteCapability nextChunkCap = us.withMapKey(getNextChunkLocation(us.rBaseKey, streamSecret, us.getMapKey(), hasher));
        return getNextChunk(version, nextChunkCap, network);
    }

    public CompletableFuture<Optional<RetrievedCapability>> getNextChunk(Snapshot version,
                                                                         AbsoluteCapability nextChunkCap,
                                                                         NetworkAccess network) {
        return network.getMetadata(version.get(nextChunkCap.writer).props, nextChunkCap)
                .thenApply(faOpt -> faOpt.map(fa -> new RetrievedCapability(nextChunkCap, fa)));
    }

    public CompletableFuture<Snapshot> rotateBaseReadKey(WritableAbsoluteCapability us,
                                                         Optional<byte[]> streamSecret,
                                                         Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                         RelativeCapability toParent,
                                                         SymmetricKey newBaseKey,
                                                         Hasher hasher,
                                                         NetworkAccess network,
                                                         Snapshot version,
                                                         Committer committer) {
        if (isDirectory())
            throw new IllegalStateException("Invalid operation for directory!");
        // keep the same data key, just marked as dirty
        SymmetricKey dataKey = this.getDataKey(us.rBaseKey).makeDirty();

        RelativeCapability nextChunk = RelativeCapability.buildSubsequentChunk(getNextChunkLocation(us.rBaseKey,
                streamSecret, us.getMapKey(), hasher), newBaseKey);
        Optional<SymmetricLinkToSigner> linkToSigner = getBaseBlock(us.rBaseKey).signer;
        CryptreeNode fa = CryptreeNode.createFile(committedHash(), linkToSigner, newBaseKey, dataKey, getProperties(us.rBaseKey),
                childrenOrData, toParent, nextChunk);
        SigningPrivateKeyAndPublicHash signer = getSigner(us.rBaseKey, us.wBaseKey.get(), entryWriter);
        return IpfsTransaction.call(us.owner,
                tid -> network.uploadChunk(version, committer, fa, us.owner, us.getMapKey(), signer, tid),
                network.dhtClient);
    }

    public CompletableFuture<Snapshot> rotateBaseFileWriteKey(AbsoluteCapability us,
                                                              SigningPrivateKeyAndPublicHash signer,
                                                              SymmetricKey newBaseWriteKey,
                                                              NetworkAccess network,
                                                              Snapshot version,
                                                              Committer committer) {
        FromBase baseBlock = getBaseBlock(us.rBaseKey);
        if (! baseBlock.signer.isPresent())
            return CompletableFuture.completedFuture(version);

        CryptreeNode fa = this.withWriterLink(us.rBaseKey, SymmetricLinkToSigner.fromPair(newBaseWriteKey, signer));
        return IpfsTransaction.call(us.owner,
                tid -> network.uploadChunk(version, committer, fa, us.owner, us.getMapKey(), signer, tid),
                network.dhtClient);
    }

    public CompletableFuture<Snapshot> rotateWriteKeys(boolean updateParent,
                                                       CryptreeNode parent,
                                                       WritableAbsoluteCapability parentCap,
                                                       SigningPrivateKeyAndPublicHash parentSigner,
                                                       Optional<SymmetricKey> suppliedBaseWriteKey,
                                                       WritableAbsoluteCapability cap,
                                                       SigningPrivateKeyAndPublicHash signer,
                                                       NetworkAccess network,
                                                       Crypto crypto,
                                                       Snapshot version,
                                                       Committer committer) {
        SymmetricKey newBaseWriteKey = suppliedBaseWriteKey.orElseGet(SymmetricKey::random);
        WritableAbsoluteCapability ourNewPointer = cap.toWritable(newBaseWriteKey);
        PublicKeyHash owner = cap.owner;
        PublicKeyHash writer = cap.writer;

        if (isDirectory()) {
            Optional<SymmetricLinkToSigner> updatedWriter = getWriterLink(cap.rBaseKey)
                    .map(toSigner -> SymmetricLinkToSigner.fromPair(newBaseWriteKey, toSigner.target(cap.wBaseKey.get())));
            CryptreeNode.DirAndChildren updatedDirAccess = withWriterLink(cap.rBaseKey, updatedWriter)
                    .withChildren(cap.rBaseKey, CryptreeNode.ChildrenLinks.empty(), crypto.hasher);

            byte[] nextChunkMapKey = getNextChunkLocation(cap.rBaseKey, Optional.empty(), null, null);
            WritableAbsoluteCapability nextChunkCap = cap.withMapKey(nextChunkMapKey);

            CryptreeNode theNewUs = updatedDirAccess.dir;

            // clean all subtree write keys
            return version.withWriter(owner, writer, network)
                    .thenCompose(originalVersion -> IpfsTransaction.call(owner,
                            tid -> updatedDirAccess.commitChildrenLinks(ourNewPointer, signer, network, tid), network.dhtClient)
                            .thenCompose(hashes -> getDirectChildren(network, cap, originalVersion))
                            .thenCompose(childFiles -> {
                                Set<Pair<RetrievedCapability, SymmetricKey>> withNewBaseWriteKeys = childFiles.stream()
                                        .map(c -> new Pair<>(c, SymmetricKey.random()))
                                        .collect(Collectors.toSet());
                                List<WritableAbsoluteCapability> childPointers = withNewBaseWriteKeys.stream()
                                        .map(p -> p.left.capability.toWritable(p.right))
                                        .collect(Collectors.toList());

                                return Futures.reduceAll(withNewBaseWriteKeys, originalVersion,
                                        (s, pair) -> pair.left.fileAccess.rotateWriteKeys(false, theNewUs,
                                                ourNewPointer, signer, Optional.of(pair.right), (WritableAbsoluteCapability) pair.left.capability,
                                                pair.left.capability.wBaseKey
                                                        .map(w -> pair.left.fileAccess.getSigner(
                                                                pair.left.capability.rBaseKey, w, Optional.of(signer)))
                                                        .orElse(signer),
                                                network, crypto, s, committer), (a, b) -> b)
                                        .thenCompose(version2 -> theNewUs.addChildLinks(ourNewPointer, signer,
                                                childPointers, network, crypto, version2, committer));
                            }).thenCompose(updatedVersion ->
                                    // update pointer from parent to us
                                    (updateParent ?
                                            updatedVersion.withWriter(owner, parentCap.writer, network)
                                                    .thenCompose(withParent -> network.retrieveMetadata(cap, withParent)
                                                            .thenCompose(updatedUs -> parent
                                                                    .updateChildLink(withParent, committer,
                                                                            parentCap,
                                                                            parentSigner, new RetrievedCapability(cap, this),
                                                                            updatedUs.get(), network, crypto.hasher))) :
                                            CompletableFuture.completedFuture(updatedVersion))
                            ).thenCompose(updatedVersion -> {
                                return network.getMetadata(originalVersion.get(nextChunkCap.writer).props, nextChunkCap)
                                        .thenCompose(mOpt -> {
                                            if (! mOpt.isPresent())
                                                return CompletableFuture.completedFuture(updatedVersion);
                                            return mOpt.get().rotateWriteKeys(false, parent, parentCap, parentSigner,
                                                    Optional.of(newBaseWriteKey), nextChunkCap, signer, network, crypto,
                                                    updatedVersion, committer);
                                        });
                            }));
        } else {
            // Only need to do the first chunk, because only those can have writer links
            return rotateBaseFileWriteKey(cap, signer, newBaseWriteKey, network, version, committer);
        }
    }

    public static class CapAndSigner {
        public final WritableAbsoluteCapability cap;
        public final SigningPrivateKeyAndPublicHash signer;

        public CapAndSigner(WritableAbsoluteCapability cap, SigningPrivateKeyAndPublicHash signer) {
            if (! cap.writer.equals(signer.publicKeyHash))
                throw new IllegalStateException("Signer doesn't match writer!");
            this.cap = cap;
            this.signer = signer;
        }

        public CapAndSigner withCap(WritableAbsoluteCapability newCap) {
            return new CapAndSigner(newCap, signer);
        }
    }

    private CompletableFuture<Pair<Snapshot, CapAndSigner>> generateNewChildCap(
            CapAndSigner currentChild,
            CapAndSigner currentParent,
            CapAndSigner newParent,
            boolean rotateSigner,
            NetworkAccess network,
            Crypto crypto,
            Snapshot version,
            Committer committer) {
        SymmetricKey baseRead = SymmetricKey.random();
        SymmetricKey baseWrite = SymmetricKey.random();
        byte[] newMapKey = crypto.random.randomBytes(RelativeCapability.MAP_KEY_LENGTH);
        if (currentChild.cap.writer.equals(currentParent.cap.writer) || ! rotateSigner) {
            WritableAbsoluteCapability newChildCap = new WritableAbsoluteCapability(currentChild.cap.owner,
                    newParent.cap.writer, newMapKey, baseRead, baseWrite);
            return Futures.of(new Pair<>(version, newParent.withCap(newChildCap)));
        }
        SigningKeyPair newSignerPair = SigningKeyPair.random(crypto.random, crypto.signer);
        return initAndAuthoriseSigner(currentChild.cap.owner, newParent.signer, newSignerPair, network, version, committer)
                .thenApply(p -> new Pair<>(p.left, new CapAndSigner(new WritableAbsoluteCapability(currentChild.cap.owner,
                    p.right.publicKeyHash, newMapKey, baseRead, baseWrite), p.right)));
    }

    public static CompletableFuture<Pair<Snapshot, SigningPrivateKeyAndPublicHash>> initAndAuthoriseSigner(
            PublicKeyHash owner,
            SigningPrivateKeyAndPublicHash parentSigner,
            SigningKeyPair newSignerPair,
            NetworkAccess network,
            Snapshot version,
            Committer committer) {
        byte[] signature = parentSigner.secret.signatureOnly(newSignerPair.publicSigningKey.serialize());
        return IpfsTransaction.call(owner,
                tid -> network.dhtClient.putSigningKey(signature, owner, parentSigner.publicKeyHash,
                        newSignerPair.publicSigningKey, tid)
                        .thenCompose(newSignerHash -> {
                            SigningPrivateKeyAndPublicHash newSigner =
                                    new SigningPrivateKeyAndPublicHash(newSignerHash, newSignerPair.secretSigningKey);
                            CommittedWriterData cwd = version.get(parentSigner);
                            OwnerProof proof = OwnerProof.build(newSigner, parentSigner.publicKeyHash);
                            return cwd.props.addOwnedKeyAndCommit(owner, parentSigner, proof, cwd.hash, network, tid)
                                    .thenCompose(v -> WriterData.createEmpty(owner, newSigner, network.dhtClient, tid)
                                            .thenCompose(wd -> committer.commit(owner, newSigner, wd, new CommittedWriterData(MaybeMultihash.empty(), null), tid))
                                            .thenApply(s -> new Pair<>(version.mergeAndOverwriteWith(v).mergeAndOverwriteWith(s), newSigner)));
                        }), network.dhtClient);
    }

    public static CompletableFuture<Snapshot> deAuthoriseSigner(
            PublicKeyHash owner,
            SigningPrivateKeyAndPublicHash parentSigner,
            PublicKeyHash signer,
            NetworkAccess network,
            Snapshot version,
            Committer committer) {
        PublicKeyHash parentWriter = parentSigner.publicKeyHash;
        CommittedWriterData cwd = version.get(parentSigner);
        return IpfsTransaction.call(owner, tid -> cwd.props.removeOwnedKey(owner, parentSigner, signer, network.dhtClient)
                .thenCompose(wd -> committer.commit(owner, parentSigner, wd, cwd, tid)), network.dhtClient)
                .thenApply(committed -> version.withVersion(parentWriter, committed.get(parentWriter)));
    }

    /** Rotate the base read key, base write key, map key and signing key of a file or directory recursively
     *  This operation requires size(file/subtree)/1000 free space to complete
     *
     * @param network
     * @param crypto
     * @param version
     * @param committer
     * @return
     */
    public CompletableFuture<Pair<Snapshot, WritableAbsoluteCapability>> rotateAllKeys(
            boolean isFirstChunk,
            CapAndSigner us,
            CapAndSigner newUs,
            CapAndSigner parent,
            CapAndSigner newParent,
            Optional<RelativeCapability> firstChunkOrParentCap,
            Optional<byte[]> fileStreamSecret,
            boolean rotateSigner,
            NetworkAccess network,
            Crypto crypto,
            Snapshot version,
            Committer committer) {
        // If our new signer is different from the parent signer then we first need to add the new signer as an owned
        // key to authorise it to write to our storage. We also need to keep track of old signing keys to remove
        // at the end

        FileProperties props = getProperties(getParentKey(us.cap.rBaseKey));
        WritableAbsoluteCapability nextChunkCap = us.cap.withMapKey(getNextChunkLocation(us.cap.rBaseKey, props.streamSecret,
                us.cap.getMapKey(), crypto.hasher));
        Optional<byte[]> streamSecret = ! isFirstChunk ?
                fileStreamSecret :
                isDirectory ?
                        Optional.empty() :
                        Optional.of(crypto.random.randomBytes(32));
        byte[] newNextChunkMapKey = streamSecret.map(stream ->
                FileProperties.calculateNextMapKey(stream, newUs.cap.getMapKey(), crypto.hasher))
                .orElseGet(() -> crypto.random.randomBytes(RelativeCapability.MAP_KEY_LENGTH));
        WritableAbsoluteCapability newNextChunkCap = newUs.cap.withMapKey(newNextChunkMapKey);
        SymmetricKey newParentKey = isFirstChunk ? SymmetricKey.random() : firstChunkOrParentCap.get().rBaseKey;
        // only first chunks have a link to their parent
        Optional<RelativeCapability> newParentCap = isFirstChunk ?
                firstChunkOrParentCap.map(cap -> cap.withWritingKey(
                        newParent.cap.writer.equals(newUs.cap.writer) ?
                                Optional.empty() : Optional.of(newParent.cap.writer))) :
                Optional.empty();

        Optional<RelativeCapability> childCapToUs = isFirstChunk ?
                Optional.of(new RelativeCapability(Optional.empty(), newUs.cap.getMapKey(), newParentKey, Optional.empty())) :
                firstChunkOrParentCap;

        // do for subsequent chunks first
        return version.withWriter(us.cap.owner, us.cap.writer, network)
                .thenCompose(s -> getNextChunk(s, nextChunkCap, network)
                        .thenCompose(opt -> {
                            if (!opt.isPresent())
                                return Futures.of(new Pair<>(s, newNextChunkCap));
                            return opt.get().fileAccess.rotateAllKeys(false,
                                    us.withCap(nextChunkCap),
                                    newUs.withCap(newNextChunkCap),
                                    parent,
                                    newParent,
                                    childCapToUs,
                                    streamSecret,
                                    rotateSigner,
                                    network,
                                    crypto,
                                    s,
                                    committer);
                        })).thenCompose(nextChunk -> {
                    if (isDirectory()) {
                        Set<WritableAbsoluteCapability> empty = Collections.emptySet();
                        return getDirectChildren(network, us.cap, version)
                                .thenCompose(children -> Futures.reduceAll(children,
                                        new Pair<>(nextChunk.left, empty),
                                        (p, c) -> {
                                            SigningPrivateKeyAndPublicHash childSigner = c.fileAccess.getSigner(
                                                    c.capability.rBaseKey,
                                                    c.capability.wBaseKey.get(),
                                                    Optional.of(us.signer));
                                            CapAndSigner child = new CapAndSigner((WritableAbsoluteCapability) c.capability,
                                                    childSigner);
                                            return generateNewChildCap(child, us, newUs, rotateSigner, network, crypto, p.left, committer)
                                                    .thenCompose(newChild -> c.fileAccess.rotateAllKeys(
                                                            true,
                                                            child,
                                                            newChild.right,
                                                            us,
                                                            newUs,
                                                            childCapToUs,
                                                            Optional.empty(),
                                                            rotateSigner,
                                                            network,
                                                            crypto,
                                                            newChild.left,
                                                            committer)
                                                            .thenApply(updatedChild -> new Pair<>(updatedChild.left,
                                                                    Stream.concat(p.right.stream(), Stream.of(updatedChild.right))
                                                                            .collect(Collectors.toSet()))));
                                        },
                                        (x, y) -> new Pair<>(x.left.merge(y.left),
                                                Stream.concat(x.right.stream(), y.right.stream()).collect(Collectors.toSet()))))
                                .thenCompose(newChildCaps -> {
                                    // Now rotate the current chunk, with the new child pointers
                                    Optional<SigningPrivateKeyAndPublicHash> signer = !isFirstChunk |
                                            newUs.cap.writer.equals(newParent.cap.writer) ?
                                            Optional.empty() :
                                            Optional.of(newUs.signer);
                                    RelativeCapability nextChunkRel = RelativeCapability.buildSubsequentChunk(
                                            nextChunk.right.getMapKey(), newUs.cap.rBaseKey);
                                    List<RelativeCapability> relativeChildLinks = newChildCaps.right.stream()
                                            .map(newUs.cap::relativise)
                                            .collect(Collectors.toList());
                                    DirAndChildren newUsDir = createDir(MaybeMultihash.empty(), newUs.cap.rBaseKey,
                                            newUs.cap.wBaseKey.get(), signer, props, newParentCap, newParentKey,
                                            nextChunkRel, new ChildrenLinks(relativeChildLinks), crypto.hasher);
                                    return IpfsTransaction.call(us.cap.owner, tid -> newUsDir.commit(newChildCaps.left,
                                            committer, newUs.cap, newUs.signer, network, tid), network.dhtClient);
                                });
                    } else {
                        Optional<SymmetricLinkToSigner> signerLink = !isFirstChunk |
                                newUs.cap.writer.equals(newParent.cap.writer) ?
                                Optional.empty() :
                                Optional.of(SymmetricLinkToSigner.fromPair(newUs.cap.wBaseKey.get(), newUs.signer));
                        SymmetricKey dataKey = getDataKey(us.cap.rBaseKey).makeDirty();
                        CryptreeNode newFileChunk = createFile(MaybeMultihash.empty(), signerLink, newUs.cap.rBaseKey,
                                dataKey,
                                streamSecret.map(props::withNewStreamSecret).orElse(props),
                                this.childrenOrData, newParentCap, RelativeCapability.buildSubsequentChunk(
                                        nextChunk.right.getMapKey(), nextChunk.right.rBaseKey));
                        return IpfsTransaction.call(us.cap.owner, tid -> newFileChunk.commit(nextChunk.left,
                                committer, newUs.cap, newUs.signer, network, tid), network.dhtClient);
                    }
                }).thenApply(v -> new Pair<>(v, newUs.cap));
    }

    public CompletableFuture<Snapshot> cleanAndCommit(Snapshot current,
                                                      Committer committer,
                                                      WritableAbsoluteCapability cap,
                                                      WritableAbsoluteCapability newCap,
                                                      Optional<byte[]> streamSecret,
                                                      Optional<byte[]> newStreamSecret,
                                                      SigningPrivateKeyAndPublicHash writer,
                                                      SymmetricKey newDataKey,
                                                      Location parentLocation,
                                                      SymmetricKey parentParentKey,
                                                      NetworkAccess network,
                                                      Crypto crypto) {
        FileProperties props = getProperties(cap.rBaseKey);
        Optional<byte[]> finalNewSecret = streamSecret.map(x -> newStreamSecret
                .orElseGet(() -> crypto.random.randomBytes(32)));
        FileProperties updatedFileProperties = finalNewSecret
                .map(ns -> props.withNewStreamSecret(ns))
                .orElse(props);
        WritableAbsoluteCapability nextCap = cap.withMapKey(
                getNextChunkLocation(cap.rBaseKey, streamSecret, cap.getMapKey(), crypto.hasher));
        WritableAbsoluteCapability newNextCap = cap.withMapKey(
                getNextChunkLocation(cap.rBaseKey, finalNewSecret, newCap.getMapKey(), crypto.hasher));

        return retriever(cap.rBaseKey, streamSecret, cap.getMapKey(), crypto.hasher)
                .getFile(current.get(writer).props, network, crypto, cap, streamSecret, props.size, committedHash(), x -> {})
                .thenCompose(data -> {
                    int chunkSize = (int) Math.min(props.size, Chunk.MAX_SIZE);
                    byte[] chunkData = new byte[chunkSize];
                    return data.readIntoArray(chunkData, 0, chunkSize)
                            .thenCompose(read -> {
                                byte[] nonce = cap.rBaseKey.createNonce();
                                byte[] mapKey = newCap.getMapKey();

                                Chunk chunk = new Chunk(chunkData, newDataKey, mapKey, nonce);
                                LocatedChunk locatedChunk = new LocatedChunk(newCap.getLocation(), lastCommittedHash, chunk);

                                return FileUploader.uploadChunk(current, committer, writer, updatedFileProperties, parentLocation,
                                        parentParentKey, cap.rBaseKey, locatedChunk,
                                        newNextCap.getLocation(), getWriterLink(cap.rBaseKey), crypto.hasher, network, x -> {
                                        });
                            });
                }).thenCompose(updated -> network.getMetadata(updated.get(nextCap.writer).props, nextCap)
                        .thenCompose(mOpt -> {
                            if (! mOpt.isPresent())
                                return CompletableFuture.completedFuture(updated);
                            return mOpt.get().cleanAndCommit(updated, committer, nextCap, newNextCap,
                                    streamSecret, updatedFileProperties.streamSecret, writer, newDataKey,
                                    parentLocation, parentParentKey, network, crypto)
                                    .thenCompose(snapshot ->
                                            IpfsTransaction.call(cap.owner, tid -> network.deleteChunk(snapshot, committer, mOpt.get(),
                                            cap.owner, nextCap.getMapKey(), writer, tid), network.dhtClient));
                        }));
    }

    public CompletableFuture<Snapshot> addChildrenAndCommit(Snapshot current,
                                                            Committer committer,
                                                            List<RelativeCapability> targetCAPs,
                                                            WritableAbsoluteCapability us,
                                                            SigningPrivateKeyAndPublicHash signer,
                                                            NetworkAccess network,
                                                            Crypto crypto) {
        // Make sure subsequent blobs use a different transaction to obscure linkage of different parts of this dir
        return getDirectChildren(us.rBaseKey, network).thenCompose(children -> {
            if (children.size() + targetCAPs.size() > getMaxChildLinksPerBlob()) {
                return getNextChunk(current, us, network, Optional.empty(), crypto.hasher).thenCompose(nextMetablob -> {

                    if (nextMetablob.isPresent()) {
                        AbsoluteCapability nextPointer = nextMetablob.get().capability;
                        CryptreeNode nextBlob = nextMetablob.get().fileAccess;
                        return nextBlob.addChildrenAndCommit(current, committer, targetCAPs,
                                nextPointer.toWritable(us.wBaseKey.get()), signer, network, crypto);
                    } else {
                        // first fill this directory, then overflow into a new one
                        int freeSlots = getMaxChildLinksPerBlob() - children.size();
                        List<RelativeCapability> addToUs = targetCAPs.subList(0, freeSlots);
                        List<RelativeCapability> addToNext = targetCAPs.subList(freeSlots, targetCAPs.size());
                        return (addToUs.isEmpty() ?
                                CompletableFuture.completedFuture(current) :
                                addChildrenAndCommit(current, committer, addToUs, us, signer, network, crypto))
                                .thenCompose(newBase -> {
                                    // create and upload new metadata blob
                                    SymmetricKey nextSubfoldersKey = us.rBaseKey;
                                    SymmetricKey ourParentKey = getParentKey(us.rBaseKey);
                                    Optional<RelativeCapability> parentCap = getParentBlock(ourParentKey).parentLink;
                                    RelativeCapability nextChunk = RelativeCapability.buildSubsequentChunk(crypto.random.randomBytes(32), nextSubfoldersKey);
                                    List<RelativeCapability> addToNextChunk = addToNext.stream()
                                            .limit(getMaxChildLinksPerBlob())
                                            .collect(Collectors.toList());
                                    List<RelativeCapability> remaining = addToNext.stream()
                                            .skip(getMaxChildLinksPerBlob())
                                            .collect(Collectors.toList());
                                    DirAndChildren next = CryptreeNode.createDir(MaybeMultihash.empty(), nextSubfoldersKey,
                                            null, Optional.empty(), FileProperties.EMPTY, parentCap,
                                            ourParentKey, nextChunk, new ChildrenLinks(addToNextChunk), crypto.hasher);
                                    byte[] nextMapKey = getNextChunkLocation(us.rBaseKey, Optional.empty(), null, null);
                                    WritableAbsoluteCapability nextPointer = new WritableAbsoluteCapability(us.owner,
                                            us.writer, nextMapKey, nextSubfoldersKey, us.wBaseKey.get());
                                    return IpfsTransaction.call(us.owner,
                                            tid -> next.commit(newBase, committer, nextPointer, signer, network, tid)
                                                    .thenCompose(updatedBase ->
                                                            network.getMetadata(updatedBase.get(nextPointer.writer).props, nextPointer)
                                                                    .thenCompose(nextOpt -> nextOpt.get().
                                                                            addChildrenAndCommit(updatedBase, committer, remaining,
                                                                                    nextPointer, signer, network, crypto)))
                                            , network.dhtClient);
                                });
                    }
                });
            } else {
                ArrayList<RelativeCapability> newFiles = new ArrayList<>(children);
                newFiles.addAll(targetCAPs);

                return IpfsTransaction.call(us.owner,
                        tid -> withChildren(us.rBaseKey, new ChildrenLinks(newFiles), crypto.hasher)
                                .commit(current, committer, us, signer, network, tid), network.dhtClient);
            }
        });
    }

    public CompletableFuture<Snapshot> addChildLinks(WritableAbsoluteCapability cap,
                                                     SigningPrivateKeyAndPublicHash signer,
                                                     Collection<WritableAbsoluteCapability> childrenCaps,
                                                     NetworkAccess network,
                                                     Crypto crypto,
                                                     Snapshot version,
                                                     Committer committer) {
        return addChildrenAndCommit(version, committer, childrenCaps.stream()
                                .map(childCap -> cap.relativise(childCap))
                                .collect(Collectors.toList()), cap, signer, network, crypto);
    }

    public CompletableFuture<Snapshot> mkdir(Snapshot base,
                                             Committer committer,
                                             String name,
                                             NetworkAccess network,
                                             WritableAbsoluteCapability us,
                                             Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                             Optional<SymmetricKey> optionalBaseKey,
                                             Optional<SymmetricKey> optionalBaseWriteKey,
                                             Optional<byte[]> desiredMapKey,
                                             boolean isSystemFolder,
                                             Crypto crypto) {
        SymmetricKey dirReadKey = optionalBaseKey.orElseGet(SymmetricKey::random);
        SymmetricKey dirWriteKey = optionalBaseWriteKey.orElseGet(SymmetricKey::random);
        byte[] dirMapKey = desiredMapKey.orElseGet(() -> crypto.random.randomBytes(32)); // root will be stored under this in the tree
        SymmetricKey ourParentKey = this.getParentKey(us.rBaseKey);
        RelativeCapability ourCap = new RelativeCapability(Optional.empty(), us.getMapKey(), ourParentKey, Optional.empty());
        RelativeCapability nextChunk = new RelativeCapability(Optional.empty(), crypto.random.randomBytes(32), dirReadKey, Optional.empty());
        WritableAbsoluteCapability childCap = us.withBaseKey(dirReadKey).withBaseWriteKey(dirWriteKey).withMapKey(dirMapKey);
        DirAndChildren child = CryptreeNode.createDir(MaybeMultihash.empty(), dirReadKey, dirWriteKey, Optional.empty(),
                new FileProperties(name, true, "", 0, LocalDateTime.now(), isSystemFolder,
                        Optional.empty(), Optional.empty()), Optional.of(ourCap), SymmetricKey.random(), nextChunk, crypto.hasher);

        SymmetricLink toChildWriteKey = SymmetricLink.fromPair(us.wBaseKey.get(), dirWriteKey);
        // Use two transactions to not expose the child linkage
        return IpfsTransaction.call(us.owner,
                tid -> child.commit(base, committer, childCap, entryWriter, network, tid), network.dhtClient)
                .thenCompose(updatedBase -> {
                    RelativeCapability subdirPointer = new RelativeCapability(Optional.empty(), dirMapKey, dirReadKey, Optional.of(toChildWriteKey));
                    SigningPrivateKeyAndPublicHash signer = getSigner(us.rBaseKey, us.wBaseKey.get(), entryWriter);
                    return addChildrenAndCommit(updatedBase, committer, Arrays.asList(subdirPointer), us, signer, network, crypto);
                });
    }

    public CompletableFuture<Snapshot> updateChildLink(Snapshot base,
                                                       Committer committer,
                                                       WritableAbsoluteCapability ourPointer,
                                                       SigningPrivateKeyAndPublicHash signer,
                                                       RetrievedCapability original,
                                                       RetrievedCapability modified,
                                                       NetworkAccess network,
                                                       Hasher hasher) {
        return updateChildLinks(base, committer, ourPointer, signer,
                Arrays.asList(new Pair<>(original.capability, modified.capability)), network, hasher);
    }

    public CompletableFuture<Snapshot> updateChildLink(Snapshot base,
                                                       Committer committer,
                                                       WritableAbsoluteCapability ourPointer,
                                                       SigningPrivateKeyAndPublicHash signer,
                                                       AbsoluteCapability originalCap,
                                                       AbsoluteCapability modifiedCap,
                                                       NetworkAccess network,
                                                       Hasher hasher) {
        return updateChildLinks(base, committer, ourPointer, signer,
                Arrays.asList(new Pair<>(originalCap, modifiedCap)), network, hasher);
    }

    public CompletableFuture<Snapshot> updateChildLinks(Snapshot base,
                                                        Committer committer,
                                                        WritableAbsoluteCapability ourPointer,
                                                        SigningPrivateKeyAndPublicHash signer,
                                                        Collection<Pair<AbsoluteCapability, AbsoluteCapability>> childCasPairs,
                                                        NetworkAccess network,
                                                        Hasher hasher) {
        Set<Location> locsToRemove = childCasPairs.stream()
                .map(p -> p.left.getLocation())
                .collect(Collectors.toSet());
        return getDirectChildren(network, ourPointer, base).thenCompose(children -> {

            Set<Location> existingChildLocs = children.stream()
                    .map(r -> r.capability.getLocation())
                    .collect(Collectors.toSet());

            List<RelativeCapability> withRemoval = children.stream()
                    .filter(e -> ! locsToRemove.contains(e.capability.getLocation()))
                    .map(c -> ourPointer.relativise(c.capability))
                    .collect(Collectors.toList());

            List<RelativeCapability> toAdd = childCasPairs.stream()
                    .filter(p -> existingChildLocs.contains(p.left.getLocation()))
                    .map(p -> ourPointer.relativise(p.right))
                    .collect(Collectors.toList());

            Collection<Pair<AbsoluteCapability, AbsoluteCapability>> remaining = childCasPairs.stream()
                    .filter(p -> ! existingChildLocs.contains(p.left.getLocation()))
                    .collect(Collectors.toSet());

            return (! toAdd.isEmpty() ?
                    IpfsTransaction.call(ourPointer.owner,
                            tid -> withChildren(ourPointer.rBaseKey, new ChildrenLinks(Stream.concat(withRemoval.stream(), toAdd.stream())
                                    .collect(Collectors.toList())), hasher)
                                    .commit(base, committer, ourPointer, signer, network, tid),
                            network.dhtClient) :
                    CompletableFuture.completedFuture(base)).thenCompose(
                    updated -> remaining.isEmpty() ?  CompletableFuture.completedFuture(updated) :
                            getNextChunk(base, ourPointer, network, Optional.empty(), hasher)
                                    .thenCompose(nextOpt -> {
                                        byte[] nextChunkLocation = getNextChunkLocation(ourPointer.rBaseKey, Optional.empty(), ourPointer.getMapKey(), hasher);
                                        AbsoluteCapability nextChunkCap = ourPointer.withMapKey(nextChunkLocation);
                                        WritableAbsoluteCapability writableNextPointer = nextChunkCap.toWritable(ourPointer.wBaseKey.get());
                                        return nextOpt.get().fileAccess.updateChildLinks(updated, committer,
                                                writableNextPointer, signer, remaining, network, hasher);
                                    }));
        });
    }

    public CompletableFuture<Snapshot> removeChildren(Snapshot current,
                                                      Committer committer,
                                                      List<AbsoluteCapability> childrenToRemove,
                                                      WritableAbsoluteCapability ourPointer,
                                                      Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                      NetworkAccess network,
                                                      Hasher hasher) {
        Set<Location> locsToRemove = childrenToRemove.stream()
                .map(r -> r.getLocation())
                .collect(Collectors.toSet());
        return getDirectChildren(network, ourPointer, current).thenCompose(children -> {
            List<RelativeCapability> withRemoval = children.stream()
                    .filter(e -> ! locsToRemove.contains(e.capability.getLocation()))
                    .map(c -> ourPointer.relativise(c.capability))
                    .collect(Collectors.toList());

            return IpfsTransaction.call(ourPointer.owner,
                    tid -> withChildren(ourPointer.rBaseKey, new ChildrenLinks(withRemoval), hasher)
                            .commit(current, committer, ourPointer, entryWriter, network, tid),
                    network.dhtClient);
        });
    }

    public CompletableFuture<Snapshot> commit(Snapshot current,
                                              Committer committer,
                                              WritableAbsoluteCapability us,
                                              Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                              NetworkAccess network,
                                              TransactionId tid) {
        return commit(current, committer, us, getSigner(us.rBaseKey, us.wBaseKey.get(), entryWriter), network, tid);
    }

    public CompletableFuture<Snapshot> commit(Snapshot current,
                                              Committer committer,
                                              WritableAbsoluteCapability us,
                                              SigningPrivateKeyAndPublicHash signer,
                                              NetworkAccess network,
                                              TransactionId tid) {
        return network.uploadChunk(current, committer, this, us.owner, us.getMapKey(), signer, tid);
    }

    public boolean hasParentLink(SymmetricKey baseKey) {
        SymmetricKey parentKey = getParentKey(baseKey);
        return getParentBlock(parentKey).parentLink.isPresent();
    }

    public Optional<RelativeCapability> getParentCapability(SymmetricKey baseKey) {
        SymmetricKey parentKey = getParentKey(baseKey);
        return getParentBlock(parentKey).parentLink;
    }

    public CompletableFuture<RetrievedCapability> getParent(PublicKeyHash owner,
                                                            PublicKeyHash writer,
                                                            SymmetricKey baseKey,
                                                            NetworkAccess network,
                                                            Snapshot version) {
        SymmetricKey parentKey = getParentKey(baseKey);
        Optional<RelativeCapability> parentLink = getParentBlock(parentKey).parentLink;
        if (! parentLink.isPresent())
            return CompletableFuture.completedFuture(null);

        RelativeCapability relCap = parentLink.get();
        return network.retrieveMetadata(new AbsoluteCapability(owner, relCap.writer.orElse(writer), relCap.getMapKey(),
                relCap.rBaseKey, Optional.empty()), version).thenApply(res -> {
            RetrievedCapability retrievedCapability = res.get();
            return retrievedCapability;
        });
    }

    public static Pair<CryptreeNode, List<FragmentWithHash>> createFile(MaybeMultihash existingHash,
                                                                        SymmetricKey parentKey,
                                                                        SymmetricKey dataKey,
                                                                        FileProperties props,
                                                                        byte[] chunkData,
                                                                        Location parentLocation,
                                                                        SymmetricKey parentparentKey,
                                                                        RelativeCapability nextChunk,
                                                                        Hasher hasher,
                                                                        boolean allowArrayCache) {
        Pair<FragmentedPaddedCipherText, List<FragmentWithHash>> linksAndData =
                FragmentedPaddedCipherText.build(dataKey, new CborObject.CborByteArray(chunkData),
                        MIN_FRAGMENT_SIZE, Fragment.MAX_LENGTH, hasher, allowArrayCache);
        RelativeCapability toParent = new RelativeCapability(Optional.empty(), parentLocation.getMapKey(),
                parentparentKey, Optional.empty());
        CryptreeNode cryptree = createFile(existingHash, Optional.empty(), parentKey, dataKey, props,
                linksAndData.left, toParent, nextChunk);
        return new Pair<>(cryptree, linksAndData.right);
    }

    public static CryptreeNode createFile(MaybeMultihash existingHash,
                                          Optional<SymmetricLinkToSigner> signerLink,
                                          SymmetricKey parentKey,
                                          SymmetricKey dataKey,
                                          FileProperties props,
                                          FragmentedPaddedCipherText data,
                                          RelativeCapability toParentDir,
                                          RelativeCapability nextChunk) {
        return createFile(existingHash, signerLink, parentKey, dataKey, props, data, Optional.of(toParentDir), nextChunk);
    }

    public static CryptreeNode createSubsequentFileChunk(MaybeMultihash existingHash,
                                                         Optional<SymmetricLinkToSigner> signerLink,
                                                         SymmetricKey parentKey,
                                                         SymmetricKey dataKey,
                                                         FileProperties props,
                                                         FragmentedPaddedCipherText data,
                                                         RelativeCapability nextChunk) {
        return createFile(existingHash, signerLink, parentKey, dataKey, props, data, Optional.empty(), nextChunk);
    }

    private static CryptreeNode createFile(MaybeMultihash existingHash,
                                          Optional<SymmetricLinkToSigner> signerLink,
                                          SymmetricKey parentKey,
                                          SymmetricKey dataKey,
                                          FileProperties props,
                                          FragmentedPaddedCipherText data,
                                          Optional<RelativeCapability> toParentDir,
                                          RelativeCapability nextChunk) {
        if (parentKey.equals(dataKey))
            throw new IllegalStateException("A file's base key and data key must be different!");
        FromBase fromBase = new FromBase(dataKey, signerLink, nextChunk);
        FromParent fromParent = new FromParent(toParentDir, props);

        PaddedCipherText encryptedBaseBlock = PaddedCipherText.build(parentKey, fromBase, BASE_BLOCK_PADDING_BLOCKSIZE);
        PaddedCipherText encryptedParentBlock = PaddedCipherText.build(parentKey, fromParent, META_DATA_PADDING_BLOCKSIZE);
        return new CryptreeNode(existingHash, false, encryptedBaseBlock, data, encryptedParentBlock);
    }

    public static DirAndChildren createDir(MaybeMultihash lastCommittedHash,
                                           SymmetricKey rBaseKey,
                                           SymmetricKey wBaseKey,
                                           Optional<SigningPrivateKeyAndPublicHash> signingPair,
                                           FileProperties props,
                                           Optional<RelativeCapability> parentCap,
                                           SymmetricKey parentKey,
                                           RelativeCapability nextChunk,
                                           Hasher hasher) {
        return createDir(lastCommittedHash, rBaseKey, wBaseKey, signingPair, props, parentCap, parentKey, nextChunk,
                new ChildrenLinks(Collections.emptyList()), hasher);
    }

    public static DirAndChildren createDir(MaybeMultihash lastCommittedHash,
                                           SymmetricKey rBaseKey,
                                           SymmetricKey wBaseKey,
                                           Optional<SigningPrivateKeyAndPublicHash> signingPair,
                                           FileProperties props,
                                           Optional<RelativeCapability> parentCap,
                                           SymmetricKey parentKey,
                                           RelativeCapability nextChunk,
                                           ChildrenLinks children,
                                           Hasher hasher) {
        if (rBaseKey.equals(parentKey))
            throw new IllegalStateException("A directory's base key and parent key must be different!");
        Optional<SymmetricLinkToSigner> writerLink = signingPair.map(pair -> SymmetricLinkToSigner.fromPair(wBaseKey, pair));
        FromBase fromBase = new FromBase(parentKey, writerLink, nextChunk);
        FromParent fromParent = new FromParent(parentCap, props);

        PaddedCipherText encryptedBaseBlock = PaddedCipherText.build(rBaseKey, fromBase, BASE_BLOCK_PADDING_BLOCKSIZE);
        PaddedCipherText encryptedParentBlock = PaddedCipherText.build(parentKey, fromParent, META_DATA_PADDING_BLOCKSIZE);
        Pair<FragmentedPaddedCipherText, List<FragmentWithHash>> linksAndData =
                FragmentedPaddedCipherText.build(rBaseKey, children, MIN_FRAGMENT_SIZE, Fragment.MAX_LENGTH, hasher, false);
        CryptreeNode metadata = new CryptreeNode(lastCommittedHash, true, encryptedBaseBlock, linksAndData.left, encryptedParentBlock);
        return new DirAndChildren(metadata, linksAndData.right);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("v", new CborObject.CborLong(getVersion()));
        state.put("b", fromBaseKey);
        state.put("p", fromParentKey);
        state.put("d", childrenOrData);
        return CborObject.CborMap.build(state);
    }

    public static CryptreeNode fromCbor(CborObject cbor, SymmetricKey base, Multihash hash) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for CryptreeNode: " + cbor);

        CborObject.CborMap m = (CborObject.CborMap) cbor;
        int version = (int) m.getLong("v");
        if (version != CURRENT_VERSION)
            throw new IllegalStateException("Unknown cryptree version: " + version);

        PaddedCipherText fromBaseKey = m.get("b", PaddedCipherText::fromCbor);
        PaddedCipherText fromParentKey = m.get("p", PaddedCipherText::fromCbor);
        FragmentedPaddedCipherText childrenOrData = m.get("d", FragmentedPaddedCipherText::fromCbor);

        boolean isDirectory;
        try {
            // For a file the base key is the parent key
            isDirectory = fromParentKey.decrypt(base, FromParent::fromCbor).properties.isDirectory;
        } catch (Throwable t) {
            isDirectory = true;
        }
        return new CryptreeNode(MaybeMultihash.of(hash), isDirectory, fromBaseKey, childrenOrData, fromParentKey);
    }
}

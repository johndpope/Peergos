package peergos.shared.user.fs;
import java.util.logging.*;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class FileUploader implements AutoCloseable {
	private static final Logger LOG = Logger.getGlobal();

    private final String name;
    private final long offset, length;
    private final FileProperties props;
    private final SymmetricKey baseKey;
    private final SymmetricKey dataKey;
    private final long nchunks;
    private final Location parentLocation;
    private final SymmetricKey parentparentKey;
    private final ProgressConsumer<Long> monitor;
    private final AsyncReader reader; // resettable input stream
    private final byte[] firstLocation;

    @JsConstructor
    public FileUploader(String name, String mimeType, AsyncReader fileData,
                        int offsetHi, int offsetLow, int lengthHi, int lengthLow,
                        SymmetricKey baseKey,
                        SymmetricKey dataKey,
                        Location parentLocation,
                        SymmetricKey parentparentKey,
                        ProgressConsumer<Long> monitor,
                        FileProperties fileProperties,
                        byte[] firstLocation) {
        long length = (lengthLow & 0xFFFFFFFFL) + ((lengthHi & 0xFFFFFFFFL) << 32);
        if (fileProperties == null)
            this.props = new FileProperties(name, false, mimeType, length, LocalDateTime.now(),
                    false, Optional.empty(), Optional.empty());
        else
            this.props = fileProperties;
        if (baseKey == null) baseKey = SymmetricKey.random();

        long offset = (offsetLow & 0xFFFFFFFFL) + ((offsetHi & 0xFFFFFFFFL) << 32);

        // Process and upload chunk by chunk to avoid running out of RAM, in reverse order to build linked list
        this.nchunks = length > 0 ? (length + Chunk.MAX_SIZE - 1) / Chunk.MAX_SIZE : 1;
        this.name = name;
        this.offset = offset;
        this.length = length;
        this.reader = fileData;
        this.baseKey = baseKey;
        this.dataKey = dataKey;
        this.parentLocation = parentLocation;
        this.parentparentKey = parentparentKey;
        this.monitor = monitor;
        this.firstLocation = firstLocation;
    }

    public FileUploader(String name, String mimeType, AsyncReader fileData, long offset, long length,
                        SymmetricKey baseKey, SymmetricKey dataKey, Location parentLocation, SymmetricKey parentparentKey,
                        ProgressConsumer<Long> monitor, FileProperties fileProperties, byte[] firstLocation) {
        this(name, mimeType, fileData, (int)(offset >> 32), (int) offset, (int) (length >> 32), (int) length,
                baseKey, dataKey, parentLocation, parentparentKey, monitor, fileProperties, firstLocation);
    }

    public CompletableFuture<Boolean> uploadChunk(List<CompletableFuture<Snapshot>> uploadSnapshotStore,
                                                   Committer committer,
                                                   NetworkAccess network,
                                                   PublicKeyHash owner,
                                                   SigningPrivateKeyAndPublicHash writer,
                                                   int chunkIndex,
                                                   MaybeMultihash ourExistingHash,
                                                   ProgressConsumer<Long> monitor,
                                                   Hasher hasher) {
        if(chunkIndex == (int)nchunks) {
            return CompletableFuture.completedFuture(true);
        }
        LOG.info("uploading chunk: "+chunkIndex + " of "+name);
        long position = chunkIndex * Chunk.MAX_SIZE;

        long fileLength = length;
        boolean isLastChunk = fileLength < position + Chunk.MAX_SIZE;
        int length =  isLastChunk ? (int)(fileLength -  position) : Chunk.MAX_SIZE;
        byte[] data = new byte[length];
        return reader.readIntoArray(data, 0, data.length).thenCompose(b -> {
            byte[] nonce = baseKey.createNonce();
            byte[] mapKey = FileProperties.calculateMapKey(props.streamSecret.get(), firstLocation,
                    chunkIndex * Chunk.MAX_SIZE, hasher);
            Chunk chunk = new Chunk(data, dataKey, mapKey, nonce);
            LocatedChunk locatedChunk = new LocatedChunk(new Location(owner, writer.publicKeyHash, chunk.mapKey()), ourExistingHash, chunk);
            byte[] nextMapKey = FileProperties.calculateNextMapKey(props.streamSecret.get(), mapKey, hasher);
            Location nextLocation = new Location(owner, writer.publicKeyHash, nextMapKey);
            uploadChunkFast(uploadSnapshotStore.get(chunkIndex), committer, writer, props, parentLocation, parentparentKey, baseKey,
                    locatedChunk, nextLocation, Optional.empty(), hasher, network, monitor)
                    .thenApply(updatedSnapshot -> uploadSnapshotStore.get(chunkIndex + 1).complete(updatedSnapshot));
            uploadSnapshotStore.add(Futures.incomplete());
            return uploadChunk(uploadSnapshotStore, committer, network, owner, writer, chunkIndex + 1,
                ourExistingHash, monitor, hasher);
        });
    }

    public CompletableFuture<Snapshot> upload(Snapshot current,
                                              Committer committer,
                                              NetworkAccess network,
                                              PublicKeyHash owner,
                                              SigningPrivateKeyAndPublicHash writer,
                                              Hasher hasher) {
        List<CompletableFuture<Snapshot>> uploadSnapshotStore = new ArrayList<>();
        uploadSnapshotStore.add(CompletableFuture.completedFuture(current));

        CompletableFuture<Boolean> cfChunkDescriptor = uploadChunk(uploadSnapshotStore,
                committer, network, owner, writer, 0, MaybeMultihash.empty(), monitor, hasher);
        return cfChunkDescriptor.thenCompose(d -> uploadSnapshotStore.get((int)nchunks)).exceptionally(Futures::logAndThrow);

    }

    public static CompletableFuture<Snapshot> uploadChunkFast(CompletableFuture<Snapshot> currentSnapshotFuture,
                                                          Committer committer,
                                                          SigningPrivateKeyAndPublicHash writer,
                                                          FileProperties props,
                                                          Location parentLocation,
                                                          SymmetricKey parentparentKey,
                                                          SymmetricKey baseKey,
                                                          LocatedChunk chunk,
                                                          Location nextChunkLocation,
                                                          Optional<SymmetricLinkToSigner> writerLink,
                                                          Hasher hasher,
                                                          NetworkAccess network,
                                                          ProgressConsumer<Long> monitor) {
        CappedProgressConsumer progress = new CappedProgressConsumer(monitor, chunk.chunk.data().length);
        if (! writer.publicKeyHash.equals(chunk.location.writer))
            throw new IllegalStateException("Trying to write a chunk to the wrong signing key space!");
        RelativeCapability nextChunk = RelativeCapability.buildSubsequentChunk(nextChunkLocation.getMapKey(), baseKey);
        Pair<CryptreeNode, List<FragmentWithHash>> file = CryptreeNode.createFile(chunk.existingHash, baseKey,
                chunk.chunk.key(), props, chunk.chunk.data(), parentLocation, parentparentKey, nextChunk,
                hasher, network.isJavascript());

        CryptreeNode metadata = file.left.withWriterLink(baseKey, writerLink);

        List<Fragment> fragments = file.right.stream()
                .filter(f -> ! f.hash.isIdentity())
                .map(f -> f.fragment)
                .collect(Collectors.toList());

        if (fragments.size() < file.right.size())
            progress.accept((long)chunk.chunk.data().length);
        LOG.info(StringUtils.format("Uploading chunk with %d fragments\n", fragments.size()));
        final CompletableFuture<Snapshot> uploadFuture = Futures.incomplete();
        AsyncRunner.run(network.isJavascript(), () -> {
            currentSnapshotFuture.thenCompose(snapshot ->
                IpfsTransaction.call(chunk.location.owner,
                        tid -> network.uploadFragments(fragments, chunk.location.owner, writer, progress, tid)
                                .thenCompose(hashes -> network.uploadChunk(snapshot, committer, metadata, chunk.location.owner,
                                        chunk.chunk.mapKey(), writer, tid)), network.dhtClient)
                        .thenApply(updatedSnapshot -> uploadFuture.complete(updatedSnapshot))
            );
        });
        return uploadFuture;
    }


    public static CompletableFuture<Snapshot> uploadChunk(Snapshot current,
                                                          Committer committer,
                                                          SigningPrivateKeyAndPublicHash writer,
                                                          FileProperties props,
                                                          Location parentLocation,
                                                          SymmetricKey parentparentKey,
                                                          SymmetricKey baseKey,
                                                          LocatedChunk chunk,
                                                          Location nextChunkLocation,
                                                          Optional<SymmetricLinkToSigner> writerLink,
                                                          Hasher hasher,
                                                          NetworkAccess network,
                                                          ProgressConsumer<Long> monitor) {
        CappedProgressConsumer progress = new CappedProgressConsumer(monitor, chunk.chunk.data().length);
        if (!writer.publicKeyHash.equals(chunk.location.writer))
            throw new IllegalStateException("Trying to write a chunk to the wrong signing key space!");
        RelativeCapability nextChunk = RelativeCapability.buildSubsequentChunk(nextChunkLocation.getMapKey(), baseKey);
        Pair<CryptreeNode, List<FragmentWithHash>> file = CryptreeNode.createFile(chunk.existingHash, baseKey,
                chunk.chunk.key(), props, chunk.chunk.data(), parentLocation, parentparentKey, nextChunk,
                hasher, network.isJavascript());

        CryptreeNode metadata = file.left.withWriterLink(baseKey, writerLink);

        List<Fragment> fragments = file.right.stream()
                .filter(f -> !f.hash.isIdentity())
                .map(f -> f.fragment)
                .collect(Collectors.toList());

        if (fragments.size() < file.right.size())
            progress.accept((long) chunk.chunk.data().length);
        LOG.info(StringUtils.format("Uploading chunk with %d fragments\n", fragments.size()));

        return IpfsTransaction.call(chunk.location.owner,
                tid -> network.uploadFragments(fragments, chunk.location.owner, writer, progress, tid)
                        .thenCompose(hashes -> network.uploadChunk(current, committer, metadata, chunk.location.owner,
                                chunk.chunk.mapKey(), writer, tid)), network.dhtClient);
    }

    public void close() {
        reader.close();
    }
}

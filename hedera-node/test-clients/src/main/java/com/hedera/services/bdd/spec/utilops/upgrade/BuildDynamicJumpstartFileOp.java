// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.upgrade;

import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.WRAPPED_RECORD_HASHES_FILE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashesLog;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A utility operation that dynamically generates valid jumpstart data and populates config
 * properties for the {@code BlockStreamJumpStartConfig}.
 *
 * <p>This operation reads the wrapped record hashes on disk to determine the block range, then
 * replays {@code .rcd} files from genesis through a midpoint block to compute the true chained
 * hash state. This ensures the jumpstart represents the correct from-genesis hash chain, even if
 * the disk hashes file starts at a block &gt; 0 (e.g. because disk writing was enabled after
 * genesis). The migration on restart will process disk entries from {@code midpoint+1} onward.
 *
 * <p>The computed jumpstart data is exposed as config property strings in a mutable map (so
 * the restart picks them up as {@code BlockStreamJumpStartConfig} properties), and also as raw
 * bytes via an {@link AtomicReference} so that downstream operations (e.g.
 * {@link VerifyJumpstartHashOp}) can independently replay and verify the result.
 */
public class BuildDynamicJumpstartFileOp extends UtilOp {
    private static final Logger log = LogManager.getLogger(BuildDynamicJumpstartFileOp.class);

    private final AtomicReference<byte[]> contentsRef;
    private final Map<String, String> envOverrides;

    public BuildDynamicJumpstartFileOp(
            @NonNull final AtomicReference<byte[]> contentsRef, @NonNull final Map<String, String> envOverrides) {
        this.contentsRef = requireNonNull(contentsRef);
        this.envOverrides = requireNonNull(envOverrides);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var nodes = spec.targetNetworkOrThrow().nodes();

        // Read the wrapped record hashes from node 0 to determine the block range on disk
        final var node0 = nodes.getFirst();
        final var hashesFile = node0.getExternalPath(WRAPPED_RECORD_HASHES_FILE);
        final var allBytes = Files.readAllBytes(hashesFile);
        final var hashesLog = WrappedRecordFileBlockHashesLog.PROTOBUF.parse(Bytes.wrap(allBytes));
        final var entries = hashesLog.entries();
        if (entries.size() < 2) {
            throw new IllegalStateException(
                    "Need at least 2 wrapped record entries to build a jumpstart file, but found " + entries.size()
                            + " on node " + node0.getNodeId());
        }
        final long firstDiskBlock = entries.getFirst().blockNumber();
        final long lastDiskBlock = entries.getLast().blockNumber();
        log.info(
                "Loaded {} wrapped record entries from {}. Disk block range: {} to {}",
                entries.size(),
                hashesFile,
                firstDiskBlock,
                lastDiskBlock);

        // Choose a midpoint within the disk file's range. The jumpstart block must be
        // >= firstDiskBlock so the migration has disk entries to process from
        // jumpstartBlockNum+1 onward.
        final int mid = entries.size() / 2;
        final long jumpstartBlockNum = entries.get(mid - 1).blockNumber();
        log.info(
                "Using jumpstart block {} (~midpoint of disk range [{}, {}])",
                jumpstartBlockNum,
                firstDiskBlock,
                lastDiskBlock);

        // Replay .rcd files from genesis through the jumpstart block to compute the TRUE
        // chained hash. Unlike the disk hashes file (which may start at a block > 0),
        // the .rcd files must exist for ALL blocks from genesis and produce a correct chain.
        final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0L);
        final var replayResult = RcdFileBlockHashReplay.replay(spec, -1, jumpstartBlockNum, HASH_OF_ZERO, hasher);
        final var prevWrappedBlockHash = replayResult.finalChainedHash();
        log.info(
                "Computed jumpstart state via .rcd replay from genesis; "
                        + "jumpstartBlockNum={}, prevWrappedBlockHash={}..., blocksProcessed={}",
                jumpstartBlockNum,
                prevWrappedBlockHash.toHex().substring(0, 8),
                replayResult.blocksProcessed());

        final var intermediateHashes = hasher.intermediateHashingState();

        // Populate config properties for BlockStreamJumpStartConfig
        envOverrides.put("blockStream.jumpstart.blockNum", Long.toString(jumpstartBlockNum));
        envOverrides.put("blockStream.jumpstart.previousWrappedRecordBlockHash", prevWrappedBlockHash.toHex());
        envOverrides.put("blockStream.jumpstart.streamingHasherLeafCount", Long.toString(hasher.leafCount()));
        envOverrides.put("blockStream.jumpstart.streamingHasherHashCount", Integer.toString(intermediateHashes.size()));
        envOverrides.put(
                "blockStream.jumpstart.streamingHasherSubtreeHashes",
                intermediateHashes.stream().map(Bytes::toHex).collect(Collectors.joining(",")));
        log.info(
                "Set jumpstart config properties: blockNum={}, leafCount={}, hashCount={}",
                jumpstartBlockNum,
                hasher.leafCount(),
                intermediateHashes.size());

        // Also serialize the binary format for downstream verification (VerifyJumpstartHashOp)
        final var baos = new ByteArrayOutputStream();
        try (final var dout = new DataOutputStream(baos)) {
            dout.writeLong(jumpstartBlockNum);
            prevWrappedBlockHash.writeTo(dout);
            dout.writeLong(hasher.leafCount());
            dout.writeInt(intermediateHashes.size());
            for (final var hash : intermediateHashes) {
                dout.write(hash.toByteArray());
            }
        }
        contentsRef.set(baos.toByteArray());
        return false;
    }
}

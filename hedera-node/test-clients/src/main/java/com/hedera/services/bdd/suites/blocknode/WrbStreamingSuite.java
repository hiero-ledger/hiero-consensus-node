// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.BlockNodeVerbs.blockNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlocks;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.RecordFileItem;
import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.HapiBlockNode.BlockNodeConfig;
import com.hedera.services.bdd.HapiBlockNode.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * Tests that the consensus node streams Wrapped Record Block (WRB) items to a block node when the
 * {@code blockStream.streamWrappedRecordBlocks} feature flag is enabled, and that no WRB items are
 * streamed when it is disabled.
 *
 * <p>Each block streamed in WRB mode carries a {@link RecordFileItem} (field {@code record_file} of
 * {@code BlockItem}) whose {@code record_file_contents.block_number} matches the block number of the
 * carrying block; the block SHALL NOT also contain preview-block content items (event transactions,
 * transaction results/outputs, state changes). See {@code block/stream/block_item.proto}.
 */
@Tag(BLOCK_NODE)
@OrderedInIsolation
public class WrbStreamingSuite {

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            // writerMode=FILE is the production WRB configuration (preview
                            // blocks go to disk only; only WRBs stream over gRPC). If the main
                            // stream also used gRPC (FILE_AND_GRPC), its already-closed block
                            // state would collide with WRB openBlock calls in BlockBufferService
                            // (BlockBufferService#openBlock returns early for closed blocks),
                            // silently swallowing the WRB RecordFileItem. See epic sub-issue
                            // hiero-ledger/hiero-consensus-node#24775 which will flip this
                            // default.
                            "blockStream.writerMode", "FILE",
                            "blockStream.streamWrappedRecordBlocks", "true",
                            // Required for BlockRecordManagerImpl to walk the WRB emission path
                            // (see BlockRecordManagerImpl#startUserTransaction line 411).
                            "hedera.recordStream.liveWritePrevWrappedRecordHashes", "true",
                            // Forces votingBlockNumInitialized() == true so emission kicks in
                            // without waiting for migration voting to complete (a fresh subprocess
                            // network has no prior record streams, so no local migration vote
                            // would ever be submitted). Same pattern used by the WRB unit tests
                            // in BlockRecordManagerImplWrappedRecordFileBlockHashesTest.
                            "blockStream.jumpstart.blockNum", "1"
                        })
            })
    @Order(1)
    final Stream<DynamicTest> wrbHappyPathSingleNode() {
        final AtomicReference<Map<Long, RecordFileItem>> seenRef = new AtomicReference<>();
        return hapiTest(
                // Wait longer than the 10-block voting deadline so live wrapping has time to
                // kick in and emit a few WRB blocks.
                waitUntilNextBlocks(20).withBackgroundTraffic(true),
                blockNode(0).exposingRecordFileItems(seenRef::set),
                doingContextual(spec -> {
                    final Map<Long, RecordFileItem> seen = requireNonNull(seenRef.get());
                    assertFalse(
                            seen.isEmpty(),
                            "expected at least one RecordFileItem to be streamed to the simulator");
                    final long first = seen.keySet().stream().min(Long::compare).orElseThrow();
                    final long last = seen.keySet().stream().max(Long::compare).orElseThrow();
                    for (long n = first; n <= last; n++) {
                        final RecordFileItem item = seen.get(n);
                        assertNotNull(item, "missing RecordFileItem for block " + n);
                        assertEquals(
                                n,
                                item.recordFileContents().blockNumber(),
                                "RecordFileItem for block " + n + " carries mismatched block_number");
                        assertTrue(
                                !item.recordFileContents().recordStreamItems().isEmpty(),
                                "RecordFileItem for block " + n + " has no record_stream_items");
                    }
                }));
    }

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE",
                            // liveWrite + jumpstart on so the WRB build path runs, but the
                            // dedicated streaming flag is off: the node should still compute WRB
                            // hashes locally but never forward RecordFileItems to the block node.
                            "hedera.recordStream.liveWritePrevWrappedRecordHashes", "true",
                            "blockStream.jumpstart.blockNum", "1",
                            "blockStream.streamWrappedRecordBlocks", "false"
                        })
            })
    @Order(2)
    final Stream<DynamicTest> wrbDisabledProducesNoRecordFile() {
        final AtomicReference<Map<Long, RecordFileItem>> seenRef = new AtomicReference<>();
        return hapiTest(
                waitUntilNextBlocks(20).withBackgroundTraffic(true),
                blockNode(0).exposingRecordFileItems(seenRef::set),
                doingContextual(spec -> {
                    final Map<Long, RecordFileItem> seen = requireNonNull(seenRef.get());
                    assertTrue(
                            seen.isEmpty(),
                            "expected no RecordFileItems when streamWrappedRecordBlocks=false, but saw "
                                    + seen.keySet());
                }));
    }
}

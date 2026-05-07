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
 * Verifies the consensus node streams Wrapped Record Block (WRB) items to a block node iff
 * {@code blockStream.streamWrappedRecordBlocks=true}.
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
                            // writerMode=FILE is the production WRB topology (preview blocks to
                            // disk, only WRBs over gRPC); see #24775.
                            "blockStream.writerMode", "FILE",
                            "blockStream.streamWrappedRecordBlocks", "true",
                            "hedera.recordStream.liveWritePrevWrappedRecordHashes", "true",
                            // Forces votingBlockNumInitialized() so WRB emission fires without
                            // waiting for migration voting; matches the unit-test pattern in
                            // BlockRecordManagerImplWrappedRecordFileBlockHashesTest.
                            "blockStream.jumpstart.blockNum", "1"
                        })
            })
    @Order(0)
    final Stream<DynamicTest> wrbHappyPathSingleNode() {
        final AtomicReference<Map<Long, RecordFileItem>> seenRef = new AtomicReference<>();
        return hapiTest(
                waitUntilNextBlocks(20).withBackgroundTraffic(true),
                blockNode(0).exposingRecordFileItems(seenRef::set),
                doingContextual(spec -> {
                    final Map<Long, RecordFileItem> seen = requireNonNull(seenRef.get());
                    assertFalse(seen.isEmpty(), "expected at least one RecordFileItem to be streamed to the simulator");
                    final long first = seen.keySet().stream().min(Long::compare).orElseThrow();
                    final long last = seen.keySet().stream().max(Long::compare).orElseThrow();
                    for (long n = first; n <= last; n++) {
                        final RecordFileItem item = seen.get(n);
                        assertNotNull(item, "missing RecordFileItem for block " + n);
                        assertNotNull(
                                item.creationTime(), "RecordFileItem for block " + n + " is missing creation_time");
                        final var contents = item.recordFileContents();
                        assertNotNull(contents, "RecordFileItem for block " + n + " is missing record_file_contents");
                        assertEquals(
                                n,
                                contents.blockNumber(),
                                "RecordFileItem for block " + n + " carries mismatched block_number");
                        assertNotNull(
                                contents.hapiProtoVersion(),
                                "RecordFileItem for block " + n + " is missing hapi_proto_version");
                        assertNotNull(
                                contents.startObjectRunningHash(),
                                "RecordFileItem for block " + n + " is missing start_object_running_hash");
                        assertNotNull(
                                contents.endObjectRunningHash(),
                                "RecordFileItem for block " + n + " is missing end_object_running_hash");
                        assertFalse(
                                contents.recordStreamItems().isEmpty(),
                                "RecordFileItem for block " + n + " has no record_stream_items");
                        for (final var rsi : contents.recordStreamItems()) {
                            assertNotNull(
                                    rsi.transaction(),
                                    "RecordStreamItem in block " + n + " is missing its transaction");
                            assertNotNull(
                                    rsi.record(),
                                    "RecordStreamItem in block " + n + " is missing its transaction_record");
                        }
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
                            // WRB build path on but streaming flag off: items computed locally,
                            // never forwarded to the block node.
                            "hedera.recordStream.liveWritePrevWrappedRecordHashes", "true",
                            "blockStream.jumpstart.blockNum", "1",
                            "blockStream.streamWrappedRecordBlocks", "false"
                        })
            })
    @Order(1)
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

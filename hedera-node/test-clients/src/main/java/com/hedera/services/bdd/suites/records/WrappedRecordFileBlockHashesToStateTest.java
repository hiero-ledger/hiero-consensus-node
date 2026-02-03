// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.records;

import static com.hedera.services.bdd.junit.TestTags.ONLY_EMBEDDED;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hedera.hapi.node.state.blockrecords.WrappedRecordFileBlockHashes;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.GenesisHapiTest;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Embedded HAPI test that verifies wrapped record-file block hashes are enqueued to state at record block boundaries.
 */
public class WrappedRecordFileBlockHashesToStateTest {
    private static final int WRAPPED_RECORD_FILE_BLOCK_HASHES_STATE_ID =
            StateKey.KeyOneOfType.BLOCKRECORDSERVICE_I_WRAPPED_RECORD_FILE_BLOCK_HASHES.protoOrdinal();

    @GenesisHapiTest(
            bootstrapOverrides = {
                @ConfigOverride(key = "hedera.recordStream.storeWrappedRecordFileBlockHashesInState", value = "true"),
                @ConfigOverride(key = "hedera.recordStream.logPeriod", value = "1"),
            })
    @Tag(ONLY_EMBEDDED)
    @DisplayName("Enqueues wrapped record-file block hashes on record block boundary")
    final Stream<DynamicTest> enqueuesWrappedRecordFileBlockHashesOnBoundary() {
        return hapiTest(
                withOpContext((spec, opLog) -> {
                    final var queue = spec.embeddedStateOrThrow()
                            .getReadableStates(BlockRecordService.NAME)
                            .<WrappedRecordFileBlockHashes>getQueue(WRAPPED_RECORD_FILE_BLOCK_HASHES_STATE_ID);
                    assertNull(queue.peek(), "Queue should start empty at genesis");
                }),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)).via("t0"),
                getTxnRecord("t0"),
                withOpContext((spec, opLog) -> spec.sleepConsensusTime(Duration.ofSeconds(2))),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)).via("t1"),
                getTxnRecord("t1"),
                withOpContext((spec, opLog) -> {
                    final var queue = spec.embeddedStateOrThrow()
                            .getReadableStates(BlockRecordService.NAME)
                            .<WrappedRecordFileBlockHashes>getQueue(WRAPPED_RECORD_FILE_BLOCK_HASHES_STATE_ID);
                    final var entry = queue.peek();
                    assertNotNull(
                            entry, "Expected a queued WrappedRecordFileBlockHashes entry after record block close");
                    assertEquals(0L, entry.blockNumber(), "Expected the first enqueued entry to be for record block 0");
                    assertEquals(48, entry.consensusTimestampHash().length(), "SHA-384 hash should be 48 bytes");
                    assertEquals(48, entry.outputItemsTreeRootHash().length(), "SHA-384 hash should be 48 bytes");
                }));
    }
}

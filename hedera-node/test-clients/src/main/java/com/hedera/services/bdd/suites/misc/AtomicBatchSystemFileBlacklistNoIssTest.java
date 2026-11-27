// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.TestTags.ISS;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContainText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.suites.HapiSuite.ADEQUATE_FUNDS;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_SCHEDULE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SIMPLE_FEE_SCHEDULE;
import static com.hedera.services.bdd.suites.crypto.ParseableIssBlockStreamValidationOp.ISS_NODE_ID;
import static com.hedera.services.bdd.suites.regression.system.LifecycleTest.configVersionOf;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_TRANSACTION_IN_BLACKLIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;

import com.google.protobuf.ByteString;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hederahashgraph.api.proto.java.SemanticVersion;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * End-to-end check that every node keeps the same exchange rates and fee schedule after an atomic batch tries to
 * change them. System-file changes are never admitted to an atomic batch, so a batch carrying updates of the exchange
 * rates (0.0.112) and the simple fee schedule (0.0.113) is rejected with {@code BATCH_TRANSACTION_IN_BLACKLIST}. A node
 * is then restarted and reloads its facilities from committed state; it must rejoin without an invalid state
 * signature.
 */
@Tag(ISS)
class AtomicBatchSystemFileBlacklistNoIssTest implements LifecycleTest {

    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String BROKE = "broke";
    private static final long FEE_SCALE = 100;

    @HapiTest
    final Stream<DynamicTest> restartAfterRejectedSystemFileBatchDoesNotDiverge() {
        final AtomicReference<SemanticVersion> startVersion = new AtomicReference<>();
        final AtomicReference<byte[]> committedFeeSchedule = new AtomicReference<>();
        return hapiTest(
                getVersionInfo().exposingServicesVersionTo(startVersion::set),
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(BROKE).balance(0L),
                fileUpdate(EXCHANGE_RATES)
                        .payingWith(EXCHANGE_RATE_CONTROL)
                        .fee(ADEQUATE_FUNDS)
                        .contents(
                                spec -> spec.ratesProvider().rateSetWith(1, 12).toByteString()),
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FEE_SCHEDULE_CONTROL, ONE_MILLION_HBARS))
                        .fee(ONE_HUNDRED_HBARS),
                getFileContents(SIMPLE_FEE_SCHEDULE).consumedBy(committedFeeSchedule::set),
                // A batch that would change the rates and the fee schedule, followed by an inner that fails, is
                // rejected; both files and the in-memory facilities keep their committed values.
                sourcing(() -> atomicBatch(
                                fileUpdate(EXCHANGE_RATES)
                                        .contents(spec -> spec.ratesProvider()
                                                .rateSetWith(10, 121)
                                                .toByteString())
                                        .payingWith(EXCHANGE_RATE_CONTROL)
                                        .batchKey(BATCH_OPERATOR),
                                fileUpdate(SIMPLE_FEE_SCHEDULE)
                                        .contents(ByteString.copyFrom(
                                                scaledSchedule(committedFeeSchedule.get(), FEE_SCALE)))
                                        .payingWith(FEE_SCHEDULE_CONTROL)
                                        .fee(ONE_HUNDRED_HBARS)
                                        .batchKey(BATCH_OPERATOR),
                                cryptoTransfer(tinyBarsFromTo(BROKE, FUNDING, ONE_HBAR))
                                        .payingWith(BATCH_OPERATOR)
                                        .signedBy(BATCH_OPERATOR, BROKE)
                                        .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST)),
                // Let a periodic snapshot be written after the batch, so the restart starts from post-batch state.
                sleepForSeconds(30),
                // Take the node down and bring it back; it reloads its facilities from committed state.
                sourcing(() -> reconnectNode(byNodeId(ISS_NODE_ID), configVersionOf(startVersion.get()))),
                // Drive fee-charging traffic the restarted node must handle identically to its peers.
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)),
                assertHgcaaLogDoesNotContainText(byNodeId(ISS_NODE_ID), "ISS detected", Duration.ofSeconds(30)));
    }

    private static byte[] scaledSchedule(final byte[] original, final long scale) {
        try {
            final var s = FeeSchedule.PROTOBUF.parse(Bytes.wrap(original));
            final var raised = s.copyBuilder()
                    .node(s.nodeOrThrow()
                            .copyBuilder()
                            .baseFee(s.nodeOrThrow().baseFee() * scale)
                            .build())
                    .extras(s.extras().stream()
                            .map(e -> e.copyBuilder().fee(e.fee() * scale).build())
                            .toList())
                    .services(s.services().stream()
                            .map(sv -> sv.copyBuilder()
                                    .schedule(sv.schedule().stream()
                                            .map(d -> d.copyBuilder()
                                                    .baseFee(d.baseFee() * scale)
                                                    .build())
                                            .toList())
                                    .build())
                            .toList())
                    .build();
            return FeeSchedule.PROTOBUF.toBytes(raised).toByteArray();
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.SIMPLE_FEE_SCHEDULE;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.SENDER_TXN;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.triggerSchedule;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.utilops.SysFileOverrideOp;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.stream.Stream;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Two schedules expire in the same interval: a CryptoTransfer and a ConsensusSubmitMessage. Both execute with the
 * shipped simple fee schedule, and both still execute after an attempt to install a schedule whose CRYPTO_TRANSFER
 * definition lacks the ACCOUNTS extra reference, since such a schedule is rejected atomically (the file write itself
 * rolls back). The second spec still carries a {@link SysFileOverrideOp} safety net in case that rollback itself
 * regresses, so a failed assertion does not leave this network's fee schedule mutated for later tests.
 */
@Tag(INTEGRATION)
@TargetEmbeddedMode(REPEATABLE)
public class RepeatableSimpleFeeScheduleScheduledExecutionTest {
    private static final String ORIGINAL = "originalSimpleFeeSchedule";
    private static final String BROKEN = "brokenSimpleFeeSchedule";
    private static final String RECEIVER = "receiver";
    private static final String TOPIC = "topic";
    private static final long LIFETIME = 7;

    @LeakyRepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> healthyScheduleExecutesBothScheduledTransactions() {
        return hapiTest(flattened(
                setUp(),
                triggerSchedule("transferSchedule", LIFETIME + 1),
                getAccountBalance(RECEIVER).hasTinyBars(1L),
                getTopicInfo(TOPIC).hasSeqNo(1L)));
    }

    @LeakyRepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> rejectedIncompleteScheduleLeavesBothScheduledTransactionsExecuting() {
        return hapiTest(flattened(
                safetyNetRestore(),
                setUp(),
                getFileContents(SIMPLE_FEE_SCHEDULE).saveToRegistry(ORIGINAL),
                withOpContext((spec, opLog) -> {
                    final var original = FeeSchedule.PROTOBUF.parse(
                            Bytes.wrap(spec.registry().getBytes(ORIGINAL)));
                    final var broken = original.copyBuilder()
                            .services(original.services().stream()
                                    .map(service -> service.copyBuilder()
                                            .schedule(service.schedule().stream()
                                                    .map(def -> def.name() == HederaFunctionality.CRYPTO_TRANSFER
                                                            ? def.copyBuilder()
                                                                    .extras(def.extras().stream()
                                                                            .filter(ref -> ref.name() != Extra.ACCOUNTS)
                                                                            .toList())
                                                                    .build()
                                                            : def)
                                                    .toList())
                                            .build())
                                    .toList())
                            .build();
                    spec.registry()
                            .saveBytes(
                                    BROKEN,
                                    ByteString.copyFrom(
                                            FeeSchedule.PROTOBUF.toBytes(broken).toByteArray()));
                }),
                rejectedUpdate(),
                unchangedFileContent(),
                sleepForSeconds(LIFETIME + 1),
                cryptoCreate("trigger"),
                sleepForSeconds(1),
                getAccountBalance(RECEIVER).hasTinyBars(1L),
                getTopicInfo(TOPIC).hasSeqNo(1L)));
    }

    /**
     * Snapshots the file's current contents and unconditionally restores them when the spec completes, even if an
     * earlier op in the spec fails: {@code HapiSpec.exec} stops submitting ops on the first failure, so a failed
     * {@code rejectedUpdate()} assertion (for example because the rollback this suite tests for has regressed)
     * would otherwise leave the schedule mutated for every later test on this network.
     */
    private static SpecOperation safetyNetRestore() {
        return new SysFileOverrideOp(SysFileOverrideOp.Target.SIMPLE_FEES, () -> null);
    }

    /**
     * Submits the broken schedule (saved to the registry as {@value #BROKEN} by the caller) as a single FileUpdate,
     * which it fits in: it is rejected with {@code INVALID_FEE_FILE}, and the dispatch (including the file write)
     * rolls back.
     */
    private static SpecOperation rejectedUpdate() {
        return withOpContext((spec, opLog) -> allRunFor(
                spec,
                fileUpdate(SIMPLE_FEE_SCHEDULE)
                        .contents(s -> ByteString.copyFrom(s.registry().getBytes(BROKEN)))
                        .payingWith(GENESIS)
                        .hasKnownStatus(ResponseCodeEnum.INVALID_FEE_FILE)));
    }

    /**
     * Proves the rejected update never took effect: the file still holds exactly the bytes saved as ORIGINAL.
     * Compares raw bytes directly ({@code HapiGetFileContents.hasContents} decodes both sides through the default
     * charset before comparing, which can equate distinct binary content) since the schedule is binary protobuf,
     * not text.
     */
    private static SpecOperation unchangedFileContent() {
        return withOpContext((spec, opLog) -> {
            final var original = spec.registry().getBytes(ORIGINAL);
            allRunFor(
                    spec,
                    getFileContents(SIMPLE_FEE_SCHEDULE)
                            .consumedBy(actual -> Assertions.assertArrayEquals(
                                    original, actual, "Simple fee schedule file content changed")));
        });
    }

    private static SpecOperation setUp() {
        return withOpContext((spec, opLog) -> allRunFor(
                spec,
                cryptoCreate(RECEIVER).balance(0L).via(SENDER_TXN),
                createTopic(TOPIC),
                scheduleCreate("transferSchedule", cryptoTransfer(tinyBarsFromTo(GENESIS, RECEIVER, 1L)))
                        .payingWith(GENESIS)
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, LIFETIME),
                scheduleCreate("messageSchedule", submitMessageTo(TOPIC).message("scheduled"))
                        .payingWith(GENESIS)
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, LIFETIME)));
    }
}

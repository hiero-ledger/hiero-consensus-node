// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateLargeFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SIMPLE_FEE_SCHEDULE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;

/**
 * Verifies that a transfer with pre-post hooks whose balance steps fail after the pre hook calls succeeded
 * refunds the fees charged for the post hook calls that never ran, exactly as a pre hook rejection does; that a
 * transfer failing before any hook runs refunds every hook call; and that nothing is refunded when the hook calls
 * were not charged, so the failure keeps its own status.
 */
@HapiTestLifecycle
@OrderedInIsolation
public class HookFeeRefundOnTransferFailureTest {
    private static final String PAYER = "payer";
    private static final String TRUE_OWNER = "trueOwner";
    private static final String FALSE_OWNER = "falseOwner";
    private static final String RICH_OWNER = "richOwner";
    private static final String RECEIVER = "receiver";
    private static final String PLAIN_OWNER = "plainOwner";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TREASURY = "treasury";
    private static final String FT = "ft";
    private static final long HOOK_ID = 1L;
    private static final long GAS = 25_000L;

    @Contract(contract = "TruePrePostHook", creationGas = 5_000_000L)
    static SpecContract TRUE_PRE_POST_HOOK;

    @Contract(contract = "FalsePrePostHook", creationGas = 5_000_000L)
    static SpecContract FALSE_PRE_POST_HOOK;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
        testLifecycle.doAdhoc(TRUE_PRE_POST_HOOK.getInfo());
        testLifecycle.doAdhoc(FALSE_PRE_POST_HOOK.getInfo());
    }

    @HapiTest
    @Order(1)
    final Stream<DynamicTest> postHookFeesRefundedWhenTransferFailsAfterPreHooks() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TRUE_OWNER)
                        .balance(ONE_HUNDRED_HBARS)
                        .withHook(accountAllowanceHook(HOOK_ID, TRUE_PRE_POST_HOOK.name())),
                cryptoCreate(FALSE_OWNER)
                        .balance(ONE_HUNDRED_HBARS)
                        .withHook(accountAllowanceHook(HOOK_ID, FALSE_PRE_POST_HOOK.name())),
                cryptoCreate(RICH_OWNER)
                        .balance(ONE_HUNDRED_HBARS)
                        .withHook(accountAllowanceHook(HOOK_ID, TRUE_PRE_POST_HOOK.name())),
                tokenCreate(FT).initialSupply(1_000).treasury(TREASURY),
                tokenAssociate(TRUE_OWNER, FT),
                tokenAssociate(FALSE_OWNER, FT),
                tokenAssociate(RICH_OWNER, FT),
                tokenAssociate(RECEIVER, FT),
                cryptoTransfer(moving(10, FT).between(TREASURY, RICH_OWNER)),
                // (Z) pre passes, transfer succeeds, post runs: both invocations attempted
                cryptoTransfer(moving(1, FT).between(RICH_OWNER, RECEIVER))
                        .withPrePostHookFor(RICH_OWNER, HOOK_ID, GAS, "")
                        .payingWith(PAYER)
                        .signedBy(PAYER, RICH_OWNER)
                        .via("bothRan"),
                // (X) pre rejects: only the pre invocation attempted, post invocation refunded
                cryptoTransfer(moving(1, FT).between(FALSE_OWNER, RECEIVER))
                        .withPrePostHookFor(FALSE_OWNER, HOOK_ID, GAS, "")
                        .payingWith(PAYER)
                        .signedBy(PAYER, FALSE_OWNER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK)
                        .via("preRejected"),
                // (Y) pre passes, the balance step fails before post: only the pre invocation attempted
                cryptoTransfer(moving(1, FT).between(TRUE_OWNER, RECEIVER))
                        .withPrePostHookFor(TRUE_OWNER, HOOK_ID, GAS, "")
                        .payingWith(PAYER)
                        .signedBy(PAYER, TRUE_OWNER)
                        .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE)
                        .via("stepFailed"),
                withOpContext((spec, opLog) -> {
                    final var bothRan = getTxnRecord("bothRan");
                    final var preRejected = getTxnRecord("preRejected");
                    final var stepFailed = getTxnRecord("stepFailed");
                    allRunFor(spec, bothRan, preRejected, stepFailed);
                    final long feeWithBothCalls = bothRan.getResponseRecord().getTransactionFee();
                    final long feeWithPreRejected =
                            preRejected.getResponseRecord().getTransactionFee();
                    final long feeWithStepFailed =
                            stepFailed.getResponseRecord().getTransactionFee();
                    opLog.info(
                            "Fees: both calls {}, pre rejected {}, step failed {}",
                            feeWithBothCalls,
                            feeWithPreRejected,
                            feeWithStepFailed);
                    assertTrue(
                            feeWithPreRejected < feeWithBothCalls,
                            "A pre hook rejection should refund the post hook call");
                    assertEquals(
                            feeWithPreRejected,
                            feeWithStepFailed,
                            "A step failure after the pre hook calls should refund the post hook call too");
                }));
    }

    @HapiTest
    @Order(2)
    final Stream<DynamicTest> allHookFeesRefundedWhenTransferFailsBeforeAnyHookRuns() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(PLAIN_OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TRUE_OWNER)
                        .balance(ONE_HUNDRED_HBARS)
                        .withHook(accountAllowanceHook(HOOK_ID, TRUE_PRE_POST_HOOK.name())),
                tokenCreate(FT)
                        .initialSupply(1_000)
                        .treasury(TREASURY)
                        .withCustom(fractionalFee(1, 100, 10, OptionalLong.empty(), TREASURY)),
                tokenAssociate(TRUE_OWNER, FT),
                tokenAssociate(PLAIN_OWNER, FT),
                tokenAssociate(RECEIVER, FT),
                cryptoTransfer(moving(10, FT).distributing(TREASURY, TRUE_OWNER, PLAIN_OWNER)),
                // Both hook calls run
                cryptoTransfer(movingHbar(1).between(TRUE_OWNER, RECEIVER))
                        .withPrePostHookFor(TRUE_OWNER, HOOK_ID, GAS, "")
                        .payingWith(PAYER)
                        .signedBy(PAYER, TRUE_OWNER)
                        .via("hookedSuccess"),
                cryptoTransfer(movingHbar(1).between(PLAIN_OWNER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER, PLAIN_OWNER)
                        .via("plainSuccess"),
                // The custom fee assessment fails before any hook runs
                cryptoTransfer(moving(1, FT).between(TRUE_OWNER, RECEIVER))
                        .withPrePostHookFor(TRUE_OWNER, HOOK_ID, GAS, "")
                        .payingWith(PAYER)
                        .signedBy(PAYER, TRUE_OWNER)
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE)
                        .via("hookedFailure"),
                cryptoTransfer(moving(1, FT).between(PLAIN_OWNER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER, PLAIN_OWNER)
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE)
                        .via("plainFailure"),
                withOpContext((spec, opLog) -> {
                    final var hookedSuccess = getTxnRecord("hookedSuccess");
                    final var plainSuccess = getTxnRecord("plainSuccess");
                    final var hookedFailure = getTxnRecord("hookedFailure");
                    final var plainFailure = getTxnRecord("plainFailure");
                    allRunFor(spec, hookedSuccess, plainSuccess, hookedFailure, plainFailure);
                    final long hookCallsFee = hookedSuccess.getResponseRecord().getTransactionFee()
                            - plainSuccess.getResponseRecord().getTransactionFee();
                    final long hookedFailureExtra =
                            hookedFailure.getResponseRecord().getTransactionFee()
                                    - plainFailure.getResponseRecord().getTransactionFee();
                    opLog.info(
                            "Hook calls cost {} on success, {} on a failure before any hook ran",
                            hookCallsFee,
                            hookedFailureExtra);
                    // Only the larger transaction may still cost a little more, never a hook call
                    assertTrue(
                            hookedFailureExtra < hookCallsFee / 4,
                            "A failure before any hook runs should refund every hook call");
                }));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesAreFree"})
    @Order(3)
    final Stream<DynamicTest> failuresKeepTheirStatusWhenFeesAreFree() {
        return hapiTest(
                overriding("fees.simpleFeesAreFree", "true"),
                failuresWithUnattemptedHookCalls("free"),
                failuresHadTheirOwnStatus("free"));
    }

    @HapiTest
    @Order(4)
    final Stream<DynamicTest> failuresKeepTheirStatusWhenCryptoTransferIsFreeInTheSchedule() {
        final AtomicReference<ByteString> originalSchedule = new AtomicReference<>();
        return hapiTest(
                getFileContents(SIMPLE_FEE_SCHEDULE)
                        .consumedBy(bytes -> originalSchedule.set(ByteString.copyFrom(bytes))),
                updateLargeFile(GENESIS, SIMPLE_FEE_SCHEDULE, scheduleWithFreeCryptoTransfer()),
                withOpContext((spec, opLog) -> assertTrue(spec.tryReinitializingFees())),
                // Accepts FAIL_INVALID here so the original schedule is always restored before the statuses are checked
                failuresWithUnattemptedHookCalls("scheduleFree"),
                withOpContext((spec, opLog) -> {
                    allRunFor(spec, updateLargeFile(GENESIS, SIMPLE_FEE_SCHEDULE, originalSchedule.get()));
                    assertTrue(spec.tryReinitializingFees());
                }),
                failuresHadTheirOwnStatus("scheduleFree"));
    }

    /**
     * Submits transfers that fail with hook calls left unattempted: before any hook runs, at a pre hook, and in a
     * balance step between the pre and post hooks. They are checked by {@link #failuresHadTheirOwnStatus(String)}.
     */
    private static SpecOperation failuresWithUnattemptedHookCalls(@NonNull final String prefix) {
        final var payer = prefix + PAYER;
        final var trueOwner = prefix + TRUE_OWNER;
        final var falseOwner = prefix + FALSE_OWNER;
        final var receiver = prefix + RECEIVER;
        final var treasury = prefix + TREASURY;
        final var ft = prefix + FT;
        final var feeFt = prefix + "feeFt";
        return withOpContext((spec, opLog) -> allRunFor(
                spec,
                cryptoCreate(payer).balance(ONE_MILLION_HBARS),
                cryptoCreate(treasury).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(receiver).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(trueOwner)
                        .balance(ONE_HUNDRED_HBARS)
                        .withHook(accountAllowanceHook(HOOK_ID, TRUE_PRE_POST_HOOK.name())),
                cryptoCreate(falseOwner)
                        .balance(ONE_HUNDRED_HBARS)
                        .withHook(accountAllowanceHook(HOOK_ID, FALSE_PRE_POST_HOOK.name())),
                tokenCreate(ft).initialSupply(1_000).treasury(treasury),
                tokenCreate(feeFt)
                        .initialSupply(1_000)
                        .treasury(treasury)
                        .withCustom(fractionalFee(1, 100, 10, OptionalLong.empty(), treasury)),
                tokenAssociate(trueOwner, ft, feeFt),
                tokenAssociate(falseOwner, ft),
                tokenAssociate(receiver, ft, feeFt),
                cryptoTransfer(moving(10, feeFt).between(treasury, trueOwner)),
                cryptoTransfer(moving(1, feeFt).between(trueOwner, receiver))
                        .withPrePostHookFor(trueOwner, HOOK_ID, GAS, "")
                        .payingWith(payer)
                        .signedBy(payer, trueOwner)
                        .hasKnownStatusFrom(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE, FAIL_INVALID)
                        .via(prefix + "BeforeHooks"),
                cryptoTransfer(moving(1, ft).between(falseOwner, receiver))
                        .withPrePostHookFor(falseOwner, HOOK_ID, GAS, "")
                        .payingWith(payer)
                        .signedBy(payer, falseOwner)
                        .hasKnownStatusFrom(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK, FAIL_INVALID)
                        .via(prefix + "PreRejected"),
                cryptoTransfer(moving(1, ft).between(trueOwner, receiver))
                        .withPrePostHookFor(trueOwner, HOOK_ID, GAS, "")
                        .payingWith(payer)
                        .signedBy(payer, trueOwner)
                        .hasKnownStatusFrom(INSUFFICIENT_TOKEN_BALANCE, FAIL_INVALID)
                        .via(prefix + "StepFailed")));
    }

    private static SpecOperation failuresHadTheirOwnStatus(@NonNull final String prefix) {
        return withOpContext((spec, opLog) -> allRunFor(
                spec,
                getTxnRecord(prefix + "BeforeHooks")
                        .hasPriority(recordWith().status(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE)),
                getTxnRecord(prefix + "PreRejected")
                        .hasPriority(recordWith().status(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK)),
                getTxnRecord(prefix + "StepFailed").hasPriority(recordWith().status(INSUFFICIENT_TOKEN_BALANCE))));
    }

    private static ByteString scheduleWithFreeCryptoTransfer() {
        try {
            final var root = MAPPER.readTree(V0490FileSchema.loadResourceInPackage("genesis/simpleFeesSchedules.json"));
            for (final var service : root.path("services")) {
                for (final var schedule : service.path("schedule")) {
                    if (schedule instanceof ObjectNode objectNode
                            && "CryptoTransfer".equals(schedule.path("name").asText())) {
                        objectNode.put("free", true);
                    }
                }
            }
            final var schedule = FeeSchedule.JSON.parse(Bytes.wrap(MAPPER.writeValueAsBytes(root)));
            return ByteString.copyFrom(FeeSchedule.PROTOBUF.toBytes(schedule).toByteArray());
        } catch (final Exception e) {
            throw new IllegalStateException("Unable to build a schedule with a free CryptoTransfer", e);
        }
    }
}

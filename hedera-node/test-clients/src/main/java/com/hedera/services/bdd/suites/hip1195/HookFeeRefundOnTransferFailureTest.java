// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

/**
 * Verifies that a transfer with pre-post hooks whose balance steps fail after the pre hook calls succeeded
 * refunds the fees charged for the post hook calls that never ran, exactly as a pre hook rejection does.
 */
@HapiTestLifecycle
@OrderedInIsolation
public class HookFeeRefundOnTransferFailureTest {
    private static final String PAYER = "payer";
    private static final String TRUE_OWNER = "trueOwner";
    private static final String FALSE_OWNER = "falseOwner";
    private static final String RICH_OWNER = "richOwner";
    private static final String RECEIVER = "receiver";
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
}

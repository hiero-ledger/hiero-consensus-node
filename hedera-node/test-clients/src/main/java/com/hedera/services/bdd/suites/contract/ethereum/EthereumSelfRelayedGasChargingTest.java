// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAutoCreatedAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.createHollowAccountFrom;
import static com.hedera.services.bdd.suites.utils.contracts.SimpleBytesResult.bigIntResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Verifies gas charging for a self-relayed {@code EthereumTransaction} - one whose recovered EVM sender is also
 * the outer HAPI payer, so the sender and relayer resolve to a single account backed by one balance.
 *
 * <p>When the offered gas price is below the network price, {@code CustomGasCharging.chargeWithRelayer()} splits
 * the up-front cost into a sender share and a relayer share. Previously each share was validated
 * <i>independently</i> against the same pre-charge balance and then debited sequentially, so an account holding
 * enough for either share alone - but not their sum - passed validation and was only partially charged. The
 * combined up-front cost is now validated once whenever the two roles resolve to the same account, so such an
 * account is rejected before any charge or execution.
 *
 * <p>The cases below pin that behavior:
 * <ul>
 *   <li>Cases 1, 4, 5 - self-relayed calls whose balance cannot cover the combined cost are rejected with
 *       {@code INSUFFICIENT_PAYER_BALANCE} and commit no state.</li>
 *   <li>Cases 2, 3 - controls (jointly solvent self-relay, and genuinely distinct sender/relayer) that were
 *       always charged correctly and remain so.</li>
 * </ul>
 */
@Tag(SMART_CONTRACT)
@SuppressWarnings("java:S5960")
public class EthereumSelfRelayedGasChargingTest {
    private static final String SELF_RELAYED_KEY = "selfRelayedKey";
    private static final String DISTINCT_SENDER_KEY = "distinctSenderKey";
    private static final String DISTINCT_RELAYER = "distinctRelayer";
    private static final String SIMPLE_STORAGE = "SimpleStorage";
    private static final String CALL_TXN = "selfRelayedCall";

    private static final long NETWORK_GAS_PRICE = 71L;
    private static final long GAS_LIMIT = 100_000L;
    private static final long OFFERED_GAS_PRICE = 35L;
    private static final long SENDER_GAS_COST = GAS_LIMIT * OFFERED_GAS_PRICE;
    private static final long RELAYER_GAS_COST = GAS_LIMIT * (NETWORK_GAS_PRICE - OFFERED_GAS_PRICE);
    private static final long UPFRONT_GAS_COST = GAS_LIMIT * NETWORK_GAS_PRICE;

    // Passes both independent checks (>= 3_500_000 and >= 3_600_000) but cannot cover their 7_100_000 sum
    private static final long AGGREGATE_INSOLVENT_BALANCE = 5_400_000L;
    // Comfortably covers the whole 7_100_000 up-front cost
    private static final long AGGREGATE_SOLVENT_BALANCE = 7_200_000L;
    // Barely covers the larger of the two shares, leaving the sender share almost entirely unpayable
    private static final long REFUND_FLOOR_BALANCE = 3_700_000L;
    private static final long DISTINCT_RELAYER_BALANCE = 4_000_000L;
    private static final long NEW_STORAGE_VALUE = 42L;
    private static final long INITIAL_STORAGE_VALUE = 15L;

    /**
     * Case 1: one account in both roles, holding enough for either share alone but not their sum.
     *
     * <p>Such a call previously executed while collecting far less than the gas it consumed. The combined
     * up-front cost is now validated, so the call is rejected with {@code INSUFFICIENT_PAYER_BALANCE} and the
     * contract state is left untouched.
     */
    @HapiTest
    final Stream<DynamicTest> selfRelayedAggregateInsolventIsRejected() {
        return hapiTest(flattened(
                newKeyNamed(SELF_RELAYED_KEY).shape(SECP_256K1_SHAPE),
                uploadInitCode(SIMPLE_STORAGE),
                contractCreate(SIMPLE_STORAGE),
                createHollowAccountFrom(SELF_RELAYED_KEY, 2 * ONE_HUNDRED_HBARS, AGGREGATE_INSOLVENT_BALANCE),
                // Balance passes each independent share check but not their sum, so the combined-cost
                // check rejects the call before any charge is applied.
                rejectedSelfRelayedCall(SELF_RELAYED_KEY, 0L, NEW_STORAGE_VALUE),
                // State is untouched - the constructor value remains
                contractCallLocal(SIMPLE_STORAGE, "get")
                        .has(resultWith().contractCallResult(bigIntResult(INITIAL_STORAGE_VALUE)))));
    }

    /** Case 2 (control): same account in both roles, but jointly solvent -> full gas fee collected. */
    @HapiTest
    final Stream<DynamicTest> selfRelayedAggregateSolventPaysFullGasFee() {
        final var initialBalance = new AtomicLong();
        final var finalBalance = new AtomicLong();
        final var gasUsed = new AtomicLong();

        return hapiTest(flattened(
                newKeyNamed(SELF_RELAYED_KEY).shape(SECP_256K1_SHAPE),
                uploadInitCode(SIMPLE_STORAGE),
                contractCreate(SIMPLE_STORAGE),
                createHollowAccountFrom(SELF_RELAYED_KEY, 2 * ONE_HUNDRED_HBARS, AGGREGATE_SOLVENT_BALANCE),
                getAutoCreatedAccountBalance(SELF_RELAYED_KEY).exposingBalanceTo(initialBalance::set),
                selfRelayedCall(SELF_RELAYED_KEY, gasUsed),
                contractCallLocal(SIMPLE_STORAGE, "get")
                        .has(resultWith().contractCallResult(bigIntResult(NEW_STORAGE_VALUE))),
                getAliasedAccountInfo(SELF_RELAYED_KEY).has(accountWith().nonce(1L)),
                getAutoCreatedAccountBalance(SELF_RELAYED_KEY).exposingBalanceTo(finalBalance::set),
                withOpContext((spec, opLog) -> {
                    final var record = getTxnRecord(CALL_TXN);
                    allRunFor(spec, record);

                    final long transactionFee = record.getResponseRecord().getTransactionFee();
                    final long fullGasFee = gasUsed.get() * NETWORK_GAS_PRICE;
                    final long balanceDelta = initialBalance.get() - finalBalance.get();

                    opLog.info(
                            "CASE2 aggregate-solvent self-relayed: initial={}, final={}, gasUsed={}, "
                                    + "fullGasFee={}, transactionFee={}",
                            initialBalance.get(),
                            finalBalance.get(),
                            gasUsed.get(),
                            fullGasFee,
                            transactionFee);

                    assertTrue(initialBalance.get() > UPFRONT_GAS_COST);
                    assertEquals(fullGasFee, transactionFee);
                    assertEquals(transactionFee, balanceDelta);
                })));
    }

    /** Case 3 (control): distinct sender and relayer -> full gas fee collected. */
    @HapiTest
    final Stream<DynamicTest> distinctSenderAndRelayerPayFullGasFee() {
        final var initialSenderBalance = new AtomicLong();
        final var finalSenderBalance = new AtomicLong();
        final var initialRelayerBalance = new AtomicLong();
        final var finalRelayerBalance = new AtomicLong();
        final var gasUsed = new AtomicLong();

        return hapiTest(flattened(
                newKeyNamed(DISTINCT_SENDER_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(DISTINCT_RELAYER).balance(DISTINCT_RELAYER_BALANCE),
                uploadInitCode(SIMPLE_STORAGE),
                contractCreate(SIMPLE_STORAGE),
                createHollowAccountFrom(DISTINCT_SENDER_KEY, 2 * ONE_HUNDRED_HBARS, SENDER_GAS_COST),
                getAutoCreatedAccountBalance(DISTINCT_SENDER_KEY).exposingBalanceTo(initialSenderBalance::set),
                getAccountBalance(DISTINCT_RELAYER).exposingBalanceTo(initialRelayerBalance::set),
                ethereumCall(SIMPLE_STORAGE, "set", BigInteger.valueOf(NEW_STORAGE_VALUE))
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(DISTINCT_SENDER_KEY)
                        .payingWith(DISTINCT_RELAYER)
                        .nonce(0L)
                        .gasLimit(GAS_LIMIT)
                        .maxFeePerGas(OFFERED_GAS_PRICE)
                        .maxGasAllowance(RELAYER_GAS_COST)
                        .via(CALL_TXN)
                        .hasKnownStatus(SUCCESS)
                        .exposingGasTo((status, used) -> {
                            assertEquals(SUCCESS, status);
                            gasUsed.set(used);
                        }),
                contractCallLocal(SIMPLE_STORAGE, "get")
                        .has(resultWith().contractCallResult(bigIntResult(NEW_STORAGE_VALUE))),
                getAliasedAccountInfo(DISTINCT_SENDER_KEY).has(accountWith().nonce(1L)),
                getAutoCreatedAccountBalance(DISTINCT_SENDER_KEY).exposingBalanceTo(finalSenderBalance::set),
                getAccountBalance(DISTINCT_RELAYER).exposingBalanceTo(finalRelayerBalance::set),
                withOpContext((spec, opLog) -> {
                    final var record = getTxnRecord(CALL_TXN);
                    allRunFor(spec, record);

                    final long transactionFee = record.getResponseRecord().getTransactionFee();
                    final long fullGasFee = gasUsed.get() * NETWORK_GAS_PRICE;
                    final long combinedBalanceDelta = initialSenderBalance.get()
                            - finalSenderBalance.get()
                            + initialRelayerBalance.get()
                            - finalRelayerBalance.get();

                    opLog.info(
                            "CASE3 distinct sender/relayer: senderInitial={}, senderFinal={}, relayerInitial={}, "
                                    + "relayerFinal={}, gasUsed={}, fullGasFee={}, transactionFee={}, "
                                    + "combinedBalanceDelta={}",
                            initialSenderBalance.get(),
                            finalSenderBalance.get(),
                            initialRelayerBalance.get(),
                            finalRelayerBalance.get(),
                            gasUsed.get(),
                            fullGasFee,
                            transactionFee,
                            combinedBalanceDelta);

                    assertEquals(SENDER_GAS_COST, initialSenderBalance.get());
                    assertTrue(initialRelayerBalance.get() >= RELAYER_GAS_COST);
                    assertEquals(fullGasFee, transactionFee);
                    assertEquals(transactionFee, combinedBalanceDelta);
                })));
    }

    /**
     * Case 4: the balance only just covers the larger of the two shares.
     *
     * <p>Previously the sender share was silently dropped while {@code maybeRefundGiven()} still refunded from
     * the <i>nominal</i> charges, so {@code FeeAccumulator.requireRefundable()} rejected the over-large refund
     * and the dispatch resolved to FAIL_INVALID. The transaction is now rejected up front with
     * {@code INSUFFICIENT_PAYER_BALANCE}, so the refund path is never reached.
     */
    @HapiTest
    final Stream<DynamicTest> selfRelayedAtRefundFloorIsRejected() {
        return hapiTest(flattened(
                newKeyNamed(SELF_RELAYED_KEY).shape(SECP_256K1_SHAPE),
                uploadInitCode(SIMPLE_STORAGE),
                contractCreate(SIMPLE_STORAGE),
                createHollowAccountFrom(SELF_RELAYED_KEY, 2 * ONE_HUNDRED_HBARS, REFUND_FLOOR_BALANCE),
                rejectedSelfRelayedCall(SELF_RELAYED_KEY, 0L, NEW_STORAGE_VALUE),
                contractCallLocal(SIMPLE_STORAGE, "get")
                        .has(resultWith().contractCallResult(bigIntResult(INITIAL_STORAGE_VALUE)))));
    }

    /**
     * Case 5: repeatability. Because the aggregate-insolvent self-relayed call is rejected before it executes,
     * no state change is committed and the account is not charged-then-refunded back to the same balance, so
     * the partial-charge outcome cannot be repeated across successive calls.
     */
    @HapiTest
    final Stream<DynamicTest> selfRelayedPartialChargeIsNotRepeatable() {
        final var balanceBefore = new AtomicLong();
        final var balanceAfter = new AtomicLong();

        return hapiTest(flattened(
                newKeyNamed(SELF_RELAYED_KEY).shape(SECP_256K1_SHAPE),
                uploadInitCode(SIMPLE_STORAGE),
                contractCreate(SIMPLE_STORAGE),
                createHollowAccountFrom(SELF_RELAYED_KEY, 2 * ONE_HUNDRED_HBARS, AGGREGATE_INSOLVENT_BALANCE),
                getAutoCreatedAccountBalance(SELF_RELAYED_KEY).exposingBalanceTo(balanceBefore::set),
                rejectedSelfRelayedCall(SELF_RELAYED_KEY, 0L, NEW_STORAGE_VALUE),
                // No state change, and the balance is not left where a repeat call would behave identically
                contractCallLocal(SIMPLE_STORAGE, "get")
                        .has(resultWith().contractCallResult(bigIntResult(INITIAL_STORAGE_VALUE))),
                getAutoCreatedAccountBalance(SELF_RELAYED_KEY).exposingBalanceTo(balanceAfter::set),
                withOpContext((spec, opLog) -> opLog.info(
                        "CASE5: rejected self-relayed call left balance {} then {} with storage unchanged",
                        balanceBefore.get(),
                        balanceAfter.get()))));
    }

    private static HapiEthereumCall selfRelayedCall(final String key, final AtomicLong gasUsed) {
        return ethereumCall(SIMPLE_STORAGE, "set", BigInteger.valueOf(NEW_STORAGE_VALUE))
                .type(EthTxData.EthTransactionType.EIP1559)
                .signingWith(key)
                .payingWith(key)
                .sigMapPrefixes(uniqueWithFullPrefixesFor(key))
                .nonce(0L)
                .gasLimit(GAS_LIMIT)
                .maxFeePerGas(OFFERED_GAS_PRICE)
                .maxGasAllowance(RELAYER_GAS_COST)
                .via(CALL_TXN)
                .hasKnownStatus(SUCCESS)
                .exposingGasTo((status, used) -> {
                    assertEquals(SUCCESS, status);
                    gasUsed.set(used);
                });
    }

    private static HapiEthereumCall rejectedSelfRelayedCall(
            final String key, final long nonce, final long storageValue) {
        return ethereumCall(SIMPLE_STORAGE, "set", BigInteger.valueOf(storageValue))
                .type(EthTxData.EthTransactionType.EIP1559)
                .signingWith(key)
                .payingWith(key)
                .sigMapPrefixes(uniqueWithFullPrefixesFor(key))
                .nonce(nonce)
                .gasLimit(GAS_LIMIT)
                .maxFeePerGas(OFFERED_GAS_PRICE)
                .maxGasAllowance(RELAYER_GAS_COST)
                .via(CALL_TXN)
                .hasKnownStatus(INSUFFICIENT_PAYER_BALANCE);
    }
}

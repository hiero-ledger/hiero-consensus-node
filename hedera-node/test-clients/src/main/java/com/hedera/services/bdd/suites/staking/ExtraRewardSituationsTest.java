// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.staking;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.DELEGATE_CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewAccount;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewContract;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.accountAmount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.tokenTransferList;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.STAKING_REWARD;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.CONSTRUCTOR;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCall;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCreate;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Regression tests for which accounts collect their pending staking rewards when a transaction touches them without
 * changing their HBAR balance. These reward situations are kept from mono-service. Each of these pays a staked
 * account or contract its reward:
 * <ul>
 *     <li>a {@code CryptoTransfer} listing it with a zero HBAR amount;</li>
 *     <li>a {@code ContractCall} to it;</li>
 *     <li>a {@code ContractCall} to another contract that calls it;</li>
 *     <li>a {@code ContractCreate} whose constructor calls it;</li>
 *     <li>a {@code ContractCall} that moves tokens to it through the HTS system contract;</li>
 *     <li>an {@code EthereumTransaction} calling it.</li>
 * </ul>
 *
 * <p>The same transactions scheduled (all but the {@code EthereumTransaction}, which can't be scheduled) pay the same
 * rewards when they execute immediately, on the {@code ScheduleCreate} or on the {@code ScheduleSign} that adds the
 * last signature. The scheduled transaction doesn't settle staking rewards itself; its trigger pays each staker once and
 * records the reward, leaving nothing for the staker's next transaction. The same holds for a scheduled
 * {@code ContractCall} that does change a staker's HBAR balance.
 *
 * <p>Every staker holds the same stake for the same periods, so each reward paid is the same.
 */
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
class ExtraRewardSituationsTest {
    private static final String MULTIPURPOSE = "Multipurpose";
    private static final String TRANSFER_CONTRACT = "CryptoTransfer";
    private static final String INTERNAL_CALLER = "InternalCaller";
    private static final String CALLEE = "SendInternalAndDelegate";
    private static final String CALLING_CONSTRUCTOR = "NestedLazyCreateViaConstructor";
    private static final String BELIEVE_IN_ABI = getABIFor(FUNCTION, "believeIn", MULTIPURPOSE);
    private static final String TRANSFER_MULTIPLE_TOKENS_ABI =
            getABIFor(FUNCTION, "transferMultipleTokens", TRANSFER_CONTRACT);
    private static final String CALL_EXTERNAL_FUNCTION_ABI =
            getABIFor(FUNCTION, "callExternalFunction", INTERNAL_CALLER);
    private static final String SEND_TO_ABI = getABIFor(FUNCTION, "sendTo", INTERNAL_CALLER);
    private static final String CALLING_CONSTRUCTOR_ABI = getABIFor(CONSTRUCTOR, "", CALLING_CONSTRUCTOR);
    private static final KeyShape DELEGATE_CONTRACT_KEY_SHAPE =
            KeyShape.threshOf(1, KeyShape.SIMPLE, DELEGATE_CONTRACT);
    private static final long GAS = 1_000_000L;
    private static final long LUCKY_NO = 42L;
    // InternalCaller.sendTo moves exactly one tinybar
    private static final long SENT = 1L;
    // Balance snapshots: before the direct transactions, after them, after the scheduled ones, after the follow-ups
    private static final int START = 0;
    private static final int AFTER_DIRECT = 1;
    private static final int AFTER_SCHEDULED = 2;
    private static final int AFTER_FOLLOW_UP = 3;

    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS},
            overrides = {
                "staking.startThreshold",
                "staking.perHbarRewardRate",
                "staking.rewardBalanceThreshold",
                "staking.requireMinStakeToReward"
            })
    final Stream<DynamicTest> scheduleCreateExecutionPaysTheSameRewardsAsDirectTransactions() {
        return scheduledTransactionsPayTheSameRewardsAsDirectOnes("extraByCreate", false);
    }

    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS},
            overrides = {
                "staking.startThreshold",
                "staking.perHbarRewardRate",
                "staking.rewardBalanceThreshold",
                "staking.requireMinStakeToReward"
            })
    final Stream<DynamicTest> scheduleSignExecutionPaysTheSameRewardsAsDirectTransactions() {
        return scheduledTransactionsPayTheSameRewardsAsDirectOnes("extraBySign", true);
    }

    /**
     * The contracts and accounts each case uses; every staker has a twin touched first through a schedule.
     */
    private record Names(
            String prefix,
            String payer,
            String schedulePayer,
            String relayer,
            String ecdsaKey,
            String token,
            String tokenSender,
            String transferContract,
            String internalCaller) {
        Names(final String prefix) {
            this(
                    prefix,
                    prefix + "Payer",
                    prefix + "SchedulePayer",
                    prefix + "Relayer",
                    prefix + "EcdsaKey",
                    prefix + "Token",
                    prefix + "TokenSender",
                    prefix + "TransferContract",
                    prefix + "InternalCaller");
        }

        String zeroListed() {
            return prefix + "ZeroListed";
        }

        String calledContract() {
            return prefix + "CalledContract";
        }

        String nestedCallee() {
            return prefix + "NestedCallee";
        }

        String constructorCallee() {
            return prefix + "ConstructorCallee";
        }

        String tokenReceiver() {
            return prefix + "TokenReceiver";
        }

        String hbarReceiver() {
            return prefix + "HbarReceiver";
        }

        String ethereumCalledContract() {
            return prefix + "EthereumCalledContract";
        }

        static String twin(final String staker) {
            return staker + "Twin";
        }
    }

    private static Stream<DynamicTest> scheduledTransactionsPayTheSameRewardsAsDirectOnes(
            final String prefix, final boolean executeViaScheduleSign) {
        final var n = new Names(prefix);
        // Stakers the scheduled transactions touch without changing their HBAR balance
        final var extraStakers =
                List.of(n.zeroListed(), n.calledContract(), n.nestedCallee(), n.constructorCallee(), n.tokenReceiver());
        final var contractStakers = List.of(
                n.calledContract(),
                Names.twin(n.calledContract()),
                n.nestedCallee(),
                Names.twin(n.nestedCallee()),
                n.constructorCallee(),
                Names.twin(n.constructorCallee()),
                n.ethereumCalledContract());
        final var balances = new LinkedHashMap<String, long[]>();
        Stream.concat(extraStakers.stream(), Stream.of(n.hbarReceiver()))
                .flatMap(staker -> Stream.of(staker, Names.twin(staker)))
                .forEach(staker -> balances.put(staker, new long[4]));
        balances.put(n.ethereumCalledContract(), new long[4]);
        // In the order the schedulable cases run: the five extra reward situations, then the HBAR-moving call
        final var twins = Stream.concat(extraStakers.stream(), Stream.of(n.hbarReceiver()))
                .map(Names::twin)
                .toList();
        final var triggerRecords = new LinkedHashMap<String, AtomicReference<TransactionRecord>>();
        twins.forEach(twin -> triggerRecords.put(twin, new AtomicReference<>()));

        return hapiTest(flattened(
                overridingAllOf(Map.of(
                        "staking.startThreshold", "" + 10 * ONE_HBAR,
                        "staking.perHbarRewardRate", "6849",
                        "staking.rewardBalanceThreshold", "0",
                        "staking.requireMinStakeToReward", "false")),
                cryptoTransfer(tinyBarsFromTo(GENESIS, STAKING_REWARD, ONE_MILLION_HBARS)),
                cryptoCreate(n.payer()).balance(ONE_MILLION_HBARS),
                cryptoCreate(n.schedulePayer()).balance(ONE_MILLION_HBARS),
                cryptoCreate(n.relayer()).balance(ONE_MILLION_HBARS),
                newKeyNamed(n.ecdsaKey()).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, n.ecdsaKey(), ONE_HUNDRED_HBARS)),
                uploadInitCode(MULTIPURPOSE, TRANSFER_CONTRACT, INTERNAL_CALLER, CALLEE, CALLING_CONSTRUCTOR),
                contractCreate(n.transferContract()).bytecode(TRANSFER_CONTRACT).gas(GAS),
                contractCreate(n.internalCaller())
                        .bytecode(INTERNAL_CALLER)
                        .balance(ONE_HBAR)
                        .gas(GAS),
                newKeyNamed(prefix + "DelegateKey")
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, n.transferContract()))),
                cryptoCreate(prefix + "Treasury"),
                cryptoCreate(n.tokenSender()).key(prefix + "DelegateKey"),
                tokenCreate(n.token())
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1_000L)
                        .treasury(prefix + "Treasury"),
                tokenAssociate(n.tokenSender(), n.token()),
                cryptoTransfer(moving(100L, n.token()).between(prefix + "Treasury", n.tokenSender())),

                // Start every staker in the same staking period
                waitUntilStartOfNextStakingPeriod(1),
                balances.keySet().stream()
                        .map(staker -> contractStakers.contains(staker)
                                ? contractCreate(staker)
                                        .bytecode(
                                                staker.startsWith(n.calledContract())
                                                                || staker.equals(n.ethereumCalledContract())
                                                        ? MULTIPURPOSE
                                                        : CALLEE)
                                        .balance(ONE_HUNDRED_HBARS)
                                        .stakedNodeId(0)
                                        .gas(GAS)
                                : cryptoCreate(staker)
                                        .balance(ONE_HUNDRED_HBARS)
                                        .stakedNodeId(0))
                        .toArray(SpecOperation[]::new),
                tokenAssociate(n.tokenReceiver(), n.token()),
                tokenAssociate(Names.twin(n.tokenReceiver()), n.token()),

                // Accrue the same pending reward for every staker
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                recordBalances(balances, contractStakers, START),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        flattened(
                                touchDirectly(spec, n, false),
                                ethereumCallWithFunctionAbi(false, n.ethereumCalledContract(), BELIEVE_IN_ABI, LUCKY_NO)
                                        .type(EthTxData.EthTransactionType.EIP1559)
                                        .signingWith(n.ecdsaKey())
                                        .payingWith(n.relayer())
                                        .nonce(0)
                                        .gasLimit(GAS)))),
                recordBalances(balances, contractStakers, AFTER_DIRECT),
                withOpContext((spec, log) -> {
                    final var twinOps = touches(spec, n, true);
                    final var schedules = new SpecOperation[twinOps.size()][];
                    for (int i = 0; i < twinOps.size(); i++) {
                        schedules[i] = executeScheduled(
                                prefix + "Schedule" + i,
                                twinOps.get(i),
                                n,
                                executeViaScheduleSign,
                                triggerRecords.get(twins.get(i)));
                    }
                    allRunFor(spec, flattened((Object[]) schedules));
                }),
                recordBalances(balances, contractStakers, AFTER_SCHEDULED),
                withOpContext((spec, log) -> allRunFor(spec, touchDirectly(spec, n, true))),
                recordBalances(balances, contractStakers, AFTER_FOLLOW_UP),
                assertionsHold((spec, log) -> {
                    final var reward = gain(balances, n.zeroListed(), START, AFTER_DIRECT);
                    SoftAssertions.assertSoftly(softly -> {
                        softly.assertThat(reward)
                                .as("a staker listed with a zero amount collects a reward")
                                .isPositive();
                        Stream.concat(extraStakers.stream(), Stream.of(n.ethereumCalledContract()))
                                .forEach(staker -> softly.assertThat(gain(balances, staker, START, AFTER_DIRECT))
                                        .as("%s collects its reward from the direct transaction", staker)
                                        .isEqualTo(reward));
                        softly.assertThat(gain(balances, n.hbarReceiver(), START, AFTER_DIRECT))
                                .as("the HBAR receiver collects its reward along with the HBAR")
                                .isEqualTo(reward + SENT);
                        twins.forEach(twin -> {
                            final var sent = twin.equals(Names.twin(n.hbarReceiver())) ? SENT : 0L;
                            softly.assertThat(gain(balances, twin, AFTER_DIRECT, AFTER_SCHEDULED))
                                    .as("%s collects its reward from the scheduled transaction", twin)
                                    .isEqualTo(reward + sent);
                            softly.assertThat(gain(balances, twin, AFTER_SCHEDULED, AFTER_FOLLOW_UP))
                                    .as("%s has no reward left to collect when touched directly later", twin)
                                    .isEqualTo(sent);
                            softly.assertThat(triggerRecords.get(twin).get().getPaidStakingRewardsList())
                                    .as("the triggering transaction records the reward paid to %s", twin)
                                    .containsExactly(AccountAmount.newBuilder()
                                            .setAccountID(spec.registry().getAccountID(twin))
                                            .setAmount(reward)
                                            .build());
                        });
                    });
                })));
    }

    // The direct transactions, touching the stakers or (for the follow-up) their twins
    private static SpecOperation[] touchDirectly(final HapiSpec spec, final Names n, final boolean twins) {
        return touches(spec, n, twins).stream()
                .map(op -> (SpecOperation) op.payingWith(n.payer()))
                .toArray(SpecOperation[]::new);
    }

    // One transaction per schedulable case; the HBAR-moving contract call is last
    private static List<HapiTxnOp<?>> touches(final HapiSpec spec, final Names n, final boolean twins) {
        final var registry = spec.registry();
        final UnaryOperator<String> target = staker -> twins ? Names.twin(staker) : staker;
        return List.of(
                cryptoTransfer(movingHbar(0L).between(n.payer(), target.apply(n.zeroListed()))),
                contractCallWithFunctionAbi(target.apply(n.calledContract()), BELIEVE_IN_ABI, LUCKY_NO)
                        .gas(GAS),
                contractCallWithFunctionAbi(
                                n.internalCaller(),
                                CALL_EXTERNAL_FUNCTION_ABI,
                                mirrorAddrWith(registry.getContractId(target.apply(n.nestedCallee()))))
                        .gas(GAS),
                new HapiContractCreate(
                                n.prefix() + (twins ? "CallingConstructorTwin" : "CallingConstructor"),
                                CALLING_CONSTRUCTOR_ABI,
                                mirrorAddrWith(registry.getContractId(target.apply(n.constructorCallee()))))
                        .bytecode(CALLING_CONSTRUCTOR)
                        // Without an admin key the scheduled create needs no extra signature
                        .omitAdminKey()
                        .gas(GAS),
                oneTokenTo(spec, n, target.apply(n.tokenReceiver())),
                contractCallWithFunctionAbi(
                                n.internalCaller(),
                                SEND_TO_ABI,
                                mirrorAddrWith(registry.getAccountID(target.apply(n.hbarReceiver()))))
                        .gas(GAS));
    }

    private static SpecOperation[] recordBalances(
            final Map<String, long[]> balances, final List<String> contracts, final int snapshot) {
        return balances.entrySet().stream()
                .map(entry -> contracts.contains(entry.getKey())
                        ? viewContract(
                                entry.getKey(), contract -> entry.getValue()[snapshot] = contract.tinybarBalance())
                        : viewAccount(entry.getKey(), account -> entry.getValue()[snapshot] = account.tinybarBalance()))
                .toArray(SpecOperation[]::new);
    }

    private static long gain(final Map<String, long[]> balances, final String name, final int from, final int to) {
        return balances.get(name)[to] - balances.get(name)[from];
    }

    private static HapiContractCall oneTokenTo(final HapiSpec spec, final Names n, final String receiver) {
        final var registry = spec.registry();
        return contractCallWithFunctionAbi(n.transferContract(), TRANSFER_MULTIPLE_TOKENS_ABI, (Object) new Tuple[] {
                    tokenTransferList()
                            .forToken(registry.getTokenID(n.token()))
                            .withAccountAmounts(
                                    accountAmount(registry.getAccountID(n.tokenSender()), -1L),
                                    accountAmount(registry.getAccountID(receiver), 1L))
                            .build()
                })
                .gas(GAS);
    }

    /**
     * Runs the scheduled transaction, checks it succeeded without paying staking rewards itself, and exposes the record
     * of the transaction that triggered it.
     */
    private static SpecOperation[] executeScheduled(
            final String schedule,
            final HapiTxnOp<?> scheduledTxn,
            final Names n,
            final boolean executeViaScheduleSign,
            final AtomicReference<TransactionRecord> triggerRecord) {
        final var createTxn = schedule + "Create";
        final var triggerTxn = executeViaScheduleSign ? schedule + "Sign" : createTxn;
        final var create = scheduleCreate(schedule, scheduledTxn)
                .designatingPayer(n.schedulePayer())
                .payingWith(n.payer());
        final SpecOperation[] execute = executeViaScheduleSign
                ? new SpecOperation[] {
                    create.via(createTxn),
                    // Without the designated payer's signature the schedule waits for the ScheduleSign
                    getScheduleInfo(schedule).isNotExecuted(),
                    scheduleSign(schedule)
                            .alsoSigningWith(n.schedulePayer())
                            .payingWith(n.payer())
                            .via(triggerTxn)
                }
                : new SpecOperation[] {create.alsoSigningWith(n.schedulePayer()).via(createTxn)};
        return flattened(
                execute,
                getTxnRecord(createTxn)
                        .scheduled()
                        .hasPriority(recordWith().status(SUCCESS))
                        // Other expectations aren't checked on scheduled records, so assert on the record itself
                        .exposingTo(
                                scheduledRecord -> Assertions.assertThat(scheduledRecord.getPaidStakingRewardsList())
                                        .as("the scheduled transaction pays no staking rewards")
                                        .isEmpty()),
                getTxnRecord(triggerTxn).exposingTo(triggerRecord::set));
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hip1522;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.idAsHeadlongAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_STAKING_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MODIFYING_IMMUTABLE_CONTRACT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hederahashgraph.api.proto.java.AccountID;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Tests the account staking-configuration functions added to the Hedera Account Service by
 * <a href="https://github.com/hiero-ledger/hiero-improvement-proposals/pull/1522">HIP-1522</a>.
 *
 * <p>Two surfaces are covered. A contract configures its own account through the explicit
 * {@code IHederaAccountService} form on {@code 0x16a} — the case the HIP exists to serve, and the only one
 * reachable from a contract. An EOA uses the {@code IHRC632} facade on its own address.
 *
 * <p>The driver contract is {@code HRC1522Contract} rather than the shared {@code HRC632Contract}: that
 * fixture is deployed by the HIP-632 suites under a hard-coded {@code creationGas}, and two of them pin the
 * exact gas at which a call to it flips between {@code INSUFFICIENT_GAS} and {@code SUCCESS}, so growing it
 * breaks tests that have nothing to do with staking.
 */
@Tag(SMART_CONTRACT)
public class AccountStakingConfigurationTest {
    private static final String STAKING_FLAG = "contracts.systemContract.accountService.stakingEnabled";
    private static final String HRC1522_CONTRACT = "HRC1522Contract";
    private static final String IHRC632 = "IHRC632";
    private static final String ACCOUNT = "account";
    private static final String OTHER = "other";

    /**
     * Deliberately non-zero. HAPI reports a cleared staking target as an unset {@code staked_id} oneof, so the
     * proto getter reads 0 — which makes {@code stakedNodeId(0)} and {@code noStakingNodeId()} the very same
     * assertion, and every before/after pair in this file blind. The CI network for both smart-contract HAPI
     * tasks runs 3 nodes, so node 1 exists.
     */
    private static final long NODE_ID = 1L;
    /** The staked_node_id sentinel meaning "no node"; what unstake() leaves behind. */
    private static final long SENTINEL_NODE_ID = -1L;

    private static final Address ZERO_ADDRESS = asHeadlongAddress(new byte[20]);

    // --- The motivating case: a keyless contract staking its own balance ---------------------------

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("a contract with no admin key stakes its own balance to a node")
    final Stream<DynamicTest> keylessContractStakesItself() {
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC1522_CONTRACT),
                // No admin key: a ledger-level superuser over a custody contract is exactly what the HIP
                // exists to avoid, and contractCreate would otherwise give it one
                contractCreate(HRC1522_CONTRACT).omitAdminKey(),
                cryptoTransfer(tinyBarsFromTo(ACCOUNT, HRC1522_CONTRACT, ONE_HUNDRED_HBARS)),
                contractCall(HRC1522_CONTRACT, "stakeSelfToNodeCall", NODE_ID, true)
                        .payingWith(ACCOUNT)
                        .gas(1_000_000)
                        .via("stakeSelf"),
                getTxnRecord("stakeSelf")
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, "stakeSelfToNodeCall", HRC1522_CONTRACT),
                                                isLiteralResult(new Object[] {(long) SUCCESS.getNumber()})))),
                // The contract account really is staked now
                getContractInfo(HRC1522_CONTRACT)
                        .has(contractWith().stakedNodeId(NODE_ID).isDeclinedReward(true)));
    }

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("a contract reads back its own staking state")
    final Stream<DynamicTest> contractReadsItsOwnStakingInfo() {
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC1522_CONTRACT),
                contractCreate(HRC1522_CONTRACT).omitAdminKey(),
                contractCall(HRC1522_CONTRACT, "stakeSelfToNodeCall", NODE_ID, true)
                        .payingWith(ACCOUNT)
                        .gas(1_000_000),
                contractCall(HRC1522_CONTRACT, "getOwnStakingInfoCall")
                        .payingWith(ACCOUNT)
                        .gas(1_000_000)
                        .via("readSelf"),
                // Every field of the struct, not just the record status: declineReward true, no pending reward
                // (it declines), nothing staked to it, node 1, and no account target.
                getTxnRecord("readSelf")
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, "getOwnStakingInfoCall", HRC1522_CONTRACT),
                                                stakingInfoWith(true, 0L, 0L, NODE_ID, ZERO_ADDRESS)))));
    }

    // --- Unstaking: three spellings, one outcome ---------------------------------------------------

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("unstake(), stakeToNode(-1) and stakeToAccount(0) all clear the staking target")
    final Stream<DynamicTest> allThreeUnstakeSpellingsConverge() {
        final AtomicReference<Address> contractAddress = new AtomicReference<>();
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC1522_CONTRACT),
                contractCreate(HRC1522_CONTRACT).omitAdminKey(),
                getContractInfo(HRC1522_CONTRACT).exposingEvmAddress(cb -> contractAddress.set(asHeadlongAddress(cb))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // 1. the canonical form
                        stakeToNode(contractAddress.get(), "stake1"),
                        getContractInfo(HRC1522_CONTRACT).has(contractWith().stakedNodeId(NODE_ID)),
                        contractCall(HRC1522_CONTRACT, "unstakeCall", contractAddress.get())
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("unstake1"),
                        succeeded("unstake1", "unstakeCall"),
                        // Note HAPI reports a cleared target as an unset staked_id oneof, so the proto
                        // getter reads 0 here. getStakingInfo maps that same state to the -1 sentinel,
                        // because Solidity has no oneof; GetStakingInfoCallTest pins that encoding.
                        getContractInfo(HRC1522_CONTRACT).has(contractWith().noStakingNodeId()),
                        // 2. the node-id sentinel
                        stakeToNode(contractAddress.get(), "stake2"),
                        getContractInfo(HRC1522_CONTRACT).has(contractWith().stakedNodeId(NODE_ID)),
                        contractCall(HRC1522_CONTRACT, "stakeToNodeCall", contractAddress.get(), SENTINEL_NODE_ID)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("unstake2"),
                        succeeded("unstake2", "stakeToNodeCall"),
                        getContractInfo(HRC1522_CONTRACT).has(contractWith().noStakingNodeId()),
                        // 3. the zero address, which resolves to the 0.0.0 staked_account_id sentinel
                        stakeToNode(contractAddress.get(), "stake3"),
                        getContractInfo(HRC1522_CONTRACT).has(contractWith().stakedNodeId(NODE_ID)),
                        contractCall(HRC1522_CONTRACT, "stakeToAccountCall", contractAddress.get(), ZERO_ADDRESS)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("unstake3"),
                        succeeded("unstake3", "stakeToAccountCall"),
                        getContractInfo(HRC1522_CONTRACT)
                                .has(contractWith().noStakedAccountId().noStakingNodeId()))));
    }

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("stakeToNodeAndDeclineReward rejects a negative node id rather than unstaking")
    final Stream<DynamicTest> combinedSetterRejectsTheSentinel() {
        final AtomicReference<Address> contractAddress = new AtomicReference<>();
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC1522_CONTRACT),
                contractCreate(HRC1522_CONTRACT).omitAdminKey(),
                getContractInfo(HRC1522_CONTRACT).exposingEvmAddress(cb -> contractAddress.set(asHeadlongAddress(cb))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // Stake first, so "nothing changed" below is a real observation rather than a
                        // statement about an account that was never staked to begin with
                        stakeToNode(contractAddress.get(), "stakeFirst"),
                        getContractInfo(HRC1522_CONTRACT).has(contractWith().stakedNodeId(NODE_ID)),
                        contractCall(
                                        HRC1522_CONTRACT,
                                        "stakeToNodeAndDeclineRewardCall",
                                        contractAddress.get(),
                                        SENTINEL_NODE_ID,
                                        true)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("combinedWithSentinel"))),
                getTxnRecord("combinedWithSentinel")
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .resultThruAbi(
                                                getABIFor(
                                                        FUNCTION, "stakeToNodeAndDeclineRewardCall", HRC1522_CONTRACT),
                                                isLiteralResult(
                                                        new Object[] {(long) INVALID_STAKING_ID.getNumber()})))),
                // and the earlier staking is untouched
                getContractInfo(HRC1522_CONTRACT)
                        .has(contractWith().stakedNodeId(NODE_ID).isDeclinedReward(false)));
    }

    // --- Staking to an account ---------------------------------------------------------------------

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("a contract stakes to another account")
    final Stream<DynamicTest> stakesToARealAccount() {
        final AtomicReference<Address> contractAddress = new AtomicReference<>();
        final AtomicReference<AccountID> otherId = new AtomicReference<>();
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(OTHER).exposingCreatedIdTo(otherId::set),
                uploadInitCode(HRC1522_CONTRACT),
                contractCreate(HRC1522_CONTRACT).omitAdminKey(),
                getContractInfo(HRC1522_CONTRACT).exposingEvmAddress(cb -> contractAddress.set(asHeadlongAddress(cb))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        HRC1522_CONTRACT,
                                        "stakeToAccountCall",
                                        contractAddress.get(),
                                        idAsHeadlongAddress(otherId.get()))
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("stakeToAccount"),
                        succeeded("stakeToAccount", "stakeToAccountCall"),
                        getContractInfo(HRC1522_CONTRACT)
                                .has(contractWith().stakedAccountId(OTHER).noStakingNodeId()))));
    }

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("setDeclineReward toggles the flag without touching the staking target")
    final Stream<DynamicTest> setDeclineRewardLeavesTheTargetAlone() {
        final AtomicReference<Address> contractAddress = new AtomicReference<>();
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC1522_CONTRACT),
                contractCreate(HRC1522_CONTRACT).omitAdminKey(),
                getContractInfo(HRC1522_CONTRACT).exposingEvmAddress(cb -> contractAddress.set(asHeadlongAddress(cb))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        stakeToNode(contractAddress.get(), "stakeFirst"),
                        contractCall(HRC1522_CONTRACT, "setDeclineRewardCall", contractAddress.get(), true)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("decline"),
                        succeeded("decline", "setDeclineRewardCall"),
                        // the flag flipped and the node target survived
                        getContractInfo(HRC1522_CONTRACT)
                                .has(contractWith().stakedNodeId(NODE_ID).isDeclinedReward(true)))));
    }

    // --- Authorization -----------------------------------------------------------------------------

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("a contract cannot stake an account it does not control")
    final Stream<DynamicTest> crossAccountCallWithoutSignatureIsRejected() {
        final AtomicReference<Address> otherAddress = new AtomicReference<>();
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(OTHER).exposingCreatedIdTo(id -> otherAddress.set(idAsHeadlongAddress(id))),
                uploadInitCode(HRC1522_CONTRACT),
                contractCreate(HRC1522_CONTRACT).omitAdminKey(),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(HRC1522_CONTRACT, "stakeToNodeCall", otherAddress.get(), NODE_ID)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("crossAccount"))),
                // The dispatched CryptoUpdate fails INVALID_SIGNATURE, which the system contract layer
                // normalizes to 326 on the way back to the EVM
                getTxnRecord("crossAccount")
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, "stakeToNodeCall", HRC1522_CONTRACT),
                                                isLiteralResult(new Object[] {
                                                    (long) INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE.getNumber()
                                                })))),
                // ...and the target's staking really is untouched. The call named a *node*, so this is the
                // field that would have changed had authorization been skipped.
                getAccountInfo(OTHER).has(accountWith().noStakingNodeId().noStakedAccountId()));
    }

    // --- The IHRC632 facade, and its limit ---------------------------------------------------------

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("an EOA configures its own staking through the IHRC632 facade")
    final Stream<DynamicTest> eoaUsesTheFacadeOnItsOwnAddress() {
        final AtomicReference<AccountID> accountNum = new AtomicReference<>();
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS).exposingCreatedIdTo(accountNum::set),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCallWithFunctionAbi(
                                        String.valueOf(accountNum.get().getAccountNum()),
                                        getABIFor(FUNCTION, "stakeToNode", IHRC632),
                                        NODE_ID)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("facadeStake"))),
                getTxnRecord("facadeStake")
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, "stakeToNode", IHRC632),
                                                isLiteralResult(new Object[] {(long) SUCCESS.getNumber()})))),
                getAccountInfo(ACCOUNT).has(accountWith().stakedNodeId(NODE_ID)));
    }

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("an EOA declines rewards through the IHRC632 facade")
    final Stream<DynamicTest> eoaDeclinesRewardThroughTheFacade() {
        final AtomicReference<AccountID> accountNum = new AtomicReference<>();
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS).exposingCreatedIdTo(accountNum::set),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCallWithFunctionAbi(
                                        String.valueOf(accountNum.get().getAccountNum()),
                                        getABIFor(FUNCTION, "setDeclineReward", IHRC632),
                                        true)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("facadeDecline"))),
                getTxnRecord("facadeDecline")
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, "setDeclineReward", IHRC632),
                                                isLiteralResult(new Object[] {(long) SUCCESS.getNumber()})))),
                getAccountInfo(ACCOUNT).has(accountWith().isDeclinedReward(true)));
    }

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("the IHRC632 facade is unreachable on a contract address")
    final Stream<DynamicTest> facadeIsUnreachableOnAContractAddress() {
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC1522_CONTRACT),
                contractCreate(HRC1522_CONTRACT).omitAdminKey(),
                // The HAS proxy redirect fires only for an address carrying no contract bytecode, so this
                // calldata executes the contract's own dispatcher, finds no such selector, and reverts.
                // HRC1522Contract implements stakeToNodeCall(address,int64), not stakeToNode(int64).
                contractCallWithFunctionAbi(HRC1522_CONTRACT, getABIFor(FUNCTION, "stakeToNode", IHRC632), NODE_ID)
                        .payingWith(ACCOUNT)
                        .gas(1_000_000)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                // ...and nothing was staked, which is what makes this a proof rather than just "it reverted"
                getContractInfo(HRC1522_CONTRACT).has(contractWith().noStakingNodeId()));
    }

    // --- Backwards compatibility: HAPI behavior is unchanged ---------------------------------------

    @HapiTest
    @DisplayName("a top-level HAPI CryptoUpdate still may not name a contract account")
    final Stream<DynamicTest> hapiCryptoUpdateStillRejectsAContractAccount() {
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC1522_CONTRACT),
                contractCreate(HRC1522_CONTRACT),
                // The narrowing in CryptoUpdateHandler is gated on in-process dispatch metadata, which no
                // wire transaction can carry, so this is unaffected
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        cryptoUpdate(HRC1522_CONTRACT)
                                .newStakedNodeId(NODE_ID)
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT, HRC1522_CONTRACT)
                                .hasKnownStatus(INVALID_ACCOUNT_ID))));
    }

    @HapiTest
    @DisplayName("a top-level HAPI ContractUpdate still may not stake an immutable contract")
    final Stream<DynamicTest> hapiContractUpdateStillRejectsAnImmutableContract() {
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC1522_CONTRACT),
                contractCreate(HRC1522_CONTRACT).omitAdminKey(),
                contractUpdate(HRC1522_CONTRACT)
                        .newStakedNodeId(NODE_ID)
                        .payingWith(ACCOUNT)
                        .hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT));
    }

    // --- Feature flag ------------------------------------------------------------------------------

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("the staking functions are unavailable when the flag is off")
    final Stream<DynamicTest> unavailableWhenFlagIsOff() {
        final AtomicReference<Address> contractAddress = new AtomicReference<>();
        return hapiTest(
                overriding(STAKING_FLAG, "false"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC1522_CONTRACT),
                contractCreate(HRC1522_CONTRACT).omitAdminKey(),
                getContractInfo(HRC1522_CONTRACT).exposingEvmAddress(cb -> contractAddress.set(asHeadlongAddress(cb))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // No translator matches, so 0x16a returns empty output and the Solidity wrapper's
                        // abi.decode of an empty buffer reverts
                        contractCall(HRC1522_CONTRACT, "stakeToNodeCall", contractAddress.get(), NODE_ID)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                getContractInfo(HRC1522_CONTRACT).has(contractWith().noStakingNodeId()));
    }

    // --- Helpers -----------------------------------------------------------------------------------

    /** Stakes the contract to {@link #NODE_ID}; pair with {@link #succeeded} to assert the response code. */
    private static SpecOperation stakeToNode(final Address contractAddress, final String txn) {
        return contractCall(HRC1522_CONTRACT, "stakeToNodeCall", contractAddress, NODE_ID)
                .payingWith(ACCOUNT)
                .gas(1_000_000)
                .via(txn);
    }

    /**
     * Asserts the named call returned SUCCESS as its {@code int64} response code. The Solidity wrappers
     * deliberately omit {@code require(responseCode == SUCCESS)} so failure cases can be asserted without
     * reverting — which also means the top-level ContractCall status stays SUCCESS on a business failure, so
     * without this the calls in these specs could all fail silently.
     */
    private static SpecOperation succeeded(final String txn, final String function) {
        return getTxnRecord(txn)
                .hasPriority(recordWith()
                        .status(SUCCESS)
                        .contractCallResult(resultWith()
                                .resultThruAbi(
                                        getABIFor(FUNCTION, function, HRC1522_CONTRACT),
                                        isLiteralResult(new Object[] {(long) SUCCESS.getNumber()}))));
    }

    /**
     * Asserts every field of the HIP-1522 {@code StakingInfo} struct except {@code stakePeriodStart}, which is
     * a consensus-time-derived epoch second and so cannot be pinned to a literal; it is only required to be
     * set when the account is staked to a node.
     */
    private static Function<HapiSpec, Function<Object[], Optional<Throwable>>> stakingInfoWith(
            final boolean declineReward,
            final long pendingReward,
            final long stakedToMe,
            final long stakedNodeId,
            final Address stakedAccountId) {
        return spec -> objs -> {
            try {
                final long responseCode = (long) objs[0];
                if (responseCode != SUCCESS.getNumber()) {
                    return Optional.of(new AssertionError("Expected SUCCESS, got response code " + responseCode));
                }
                final var info = (Tuple) objs[1];
                assertField("declineReward", declineReward, info.get(0));
                assertField("pendingReward", pendingReward, info.get(2));
                assertField("stakedToMe", stakedToMe, info.get(3));
                assertField("stakedNodeId", stakedNodeId, info.get(4));
                assertField("stakedAccountId", stakedAccountId, info.get(5));
                final long stakePeriodStart = info.get(1);
                if (stakedNodeId != SENTINEL_NODE_ID && stakePeriodStart <= 0L) {
                    return Optional.of(new AssertionError(
                            "Expected a non-zero stakePeriodStart when staked to a node, got " + stakePeriodStart));
                }
                return Optional.empty();
            } catch (final Throwable t) {
                return Optional.of(t);
            }
        };
    }

    private static void assertField(final String name, final Object expected, final Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Bad %s! expected <%s> but was <%s>".formatted(name, expected, actual));
        }
    }
}

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
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hederahashgraph.api.proto.java.AccountID;
import java.util.concurrent.atomic.AtomicReference;
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
 */
@Tag(SMART_CONTRACT)
public class AccountStakingConfigurationTest {
    private static final String STAKING_FLAG = "contracts.systemContract.accountService.stakingEnabled";
    private static final String HRC632_CONTRACT = "HRC632Contract";
    private static final String IHRC632 = "IHRC632";
    private static final String ACCOUNT = "account";
    private static final String OTHER = "other";
    private static final long NODE_ID = 0L;
    /** The staked_node_id sentinel meaning "no node"; what unstake() leaves behind. */
    private static final long SENTINEL_NODE_ID = -1L;

    // --- The motivating case: a keyless contract staking its own balance ---------------------------

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("a contract with no admin key stakes its own balance to a node")
    final Stream<DynamicTest> keylessContractStakesItself() {
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC632_CONTRACT),
                // No admin key: a ledger-level superuser over a custody contract is exactly what the HIP
                // exists to avoid, and contractCreate would otherwise give it one
                contractCreate(HRC632_CONTRACT).omitAdminKey(),
                cryptoTransfer(tinyBarsFromTo(ACCOUNT, HRC632_CONTRACT, ONE_HUNDRED_HBARS)),
                contractCall(HRC632_CONTRACT, "stakeSelfToNodeCall", NODE_ID, true)
                        .payingWith(ACCOUNT)
                        .gas(1_000_000)
                        .via("stakeSelf"),
                getTxnRecord("stakeSelf")
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, "stakeSelfToNodeCall", HRC632_CONTRACT),
                                                isLiteralResult(new Object[] {(long) SUCCESS.getNumber()})))),
                // The contract account really is staked now
                getContractInfo(HRC632_CONTRACT)
                        .has(contractWith().stakedNodeId(NODE_ID).isDeclinedReward(true)));
    }

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("a contract reads back its own staking state")
    final Stream<DynamicTest> contractReadsItsOwnStakingInfo() {
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC632_CONTRACT),
                contractCreate(HRC632_CONTRACT).omitAdminKey(),
                contractCall(HRC632_CONTRACT, "stakeSelfToNodeCall", NODE_ID, true)
                        .payingWith(ACCOUNT)
                        .gas(1_000_000),
                contractCall(HRC632_CONTRACT, "getOwnStakingInfoCall")
                        .payingWith(ACCOUNT)
                        .gas(1_000_000)
                        .via("readSelf"),
                // declineReward true, no accruing reward (it declines), no account staking target
                getTxnRecord("readSelf").logged().hasPriority(recordWith().status(SUCCESS)));
    }

    // --- Unstaking: three spellings, one outcome ---------------------------------------------------

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("unstake(), stakeToNode(-1) and stakeToAccount(0) all clear the staking target")
    final Stream<DynamicTest> allThreeUnstakeSpellingsConverge() {
        final AtomicReference<Address> contractAddress = new AtomicReference<>();
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC632_CONTRACT),
                contractCreate(HRC632_CONTRACT).omitAdminKey(),
                getContractInfo(HRC632_CONTRACT).exposingEvmAddress(cb -> contractAddress.set(asHeadlongAddress(cb))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // 1. the canonical form
                        contractCall(HRC632_CONTRACT, "stakeToNodeCall", contractAddress.get(), NODE_ID)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000),
                        getContractInfo(HRC632_CONTRACT).has(contractWith().stakedNodeId(NODE_ID)),
                        contractCall(HRC632_CONTRACT, "unstakeCall", contractAddress.get())
                                .payingWith(ACCOUNT)
                                .gas(1_000_000),
                        // Note HAPI reports a cleared target as an unset staked_id oneof, so the proto
                        // getter reads 0 here. getStakingInfo maps that same state to the -1 sentinel,
                        // because Solidity has no oneof; GetStakingInfoCallTest pins that encoding.
                        getContractInfo(HRC632_CONTRACT).has(contractWith().noStakingNodeId()),
                        // 2. the node-id sentinel
                        contractCall(HRC632_CONTRACT, "stakeToNodeCall", contractAddress.get(), NODE_ID)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000),
                        contractCall(HRC632_CONTRACT, "stakeToNodeCall", contractAddress.get(), SENTINEL_NODE_ID)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000),
                        getContractInfo(HRC632_CONTRACT).has(contractWith().noStakingNodeId()),
                        // 3. the zero address, which resolves to the 0.0.0 staked_account_id sentinel
                        contractCall(HRC632_CONTRACT, "stakeToNodeCall", contractAddress.get(), NODE_ID)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000),
                        contractCall(
                                        HRC632_CONTRACT,
                                        "stakeToAccountCall",
                                        contractAddress.get(),
                                        asHeadlongAddress(new byte[20]))
                                .payingWith(ACCOUNT)
                                .gas(1_000_000),
                        getContractInfo(HRC632_CONTRACT)
                                .has(contractWith().noStakedAccountId().noStakingNodeId()))));
    }

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("stakeToNodeAndDeclineReward rejects a negative node id rather than unstaking")
    final Stream<DynamicTest> combinedSetterRejectsTheSentinel() {
        final AtomicReference<Address> contractAddress = new AtomicReference<>();
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC632_CONTRACT),
                contractCreate(HRC632_CONTRACT).omitAdminKey(),
                getContractInfo(HRC632_CONTRACT).exposingEvmAddress(cb -> contractAddress.set(asHeadlongAddress(cb))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        HRC632_CONTRACT,
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
                                                getABIFor(FUNCTION, "stakeToNodeAndDeclineRewardCall", HRC632_CONTRACT),
                                                isLiteralResult(
                                                        new Object[] {(long) INVALID_STAKING_ID.getNumber()})))),
                // and nothing changed
                getContractInfo(HRC632_CONTRACT).has(contractWith().noStakingNodeId()));
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
                uploadInitCode(HRC632_CONTRACT),
                contractCreate(HRC632_CONTRACT).omitAdminKey(),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(HRC632_CONTRACT, "stakeToNodeCall", otherAddress.get(), NODE_ID)
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
                                                getABIFor(FUNCTION, "stakeToNodeCall", HRC632_CONTRACT),
                                                isLiteralResult(new Object[] {
                                                    (long) INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE.getNumber()
                                                })))),
                getAccountInfo(OTHER).has(accountWith().noStakedAccountId()));
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
    @DisplayName("the IHRC632 facade is unreachable on a contract address")
    final Stream<DynamicTest> facadeIsUnreachableOnAContractAddress() {
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC632_CONTRACT),
                contractCreate(HRC632_CONTRACT).omitAdminKey(),
                // The HAS proxy redirect fires only for an address carrying no contract bytecode, so this
                // calldata executes the contract's own dispatcher, finds no such selector, and reverts.
                // HRC632Contract implements stakeToNodeCall(address,int64), not stakeToNode(int64).
                contractCallWithFunctionAbi(HRC632_CONTRACT, getABIFor(FUNCTION, "stakeToNode", IHRC632), NODE_ID)
                        .payingWith(ACCOUNT)
                        .gas(1_000_000)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                // ...and nothing was staked, which is what makes this a proof rather than just "it reverted"
                getContractInfo(HRC632_CONTRACT).has(contractWith().noStakingNodeId()));
    }

    // --- Backwards compatibility: HAPI behavior is unchanged ---------------------------------------

    @HapiTest
    @DisplayName("a top-level HAPI CryptoUpdate still may not name a contract account")
    final Stream<DynamicTest> hapiCryptoUpdateStillRejectsAContractAccount() {
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC632_CONTRACT),
                contractCreate(HRC632_CONTRACT),
                // The narrowing in CryptoUpdateHandler is gated on in-process dispatch metadata, which no
                // wire transaction can carry, so this is unaffected
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        cryptoUpdate(HRC632_CONTRACT)
                                .newStakedNodeId(NODE_ID)
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT, HRC632_CONTRACT)
                                .hasKnownStatus(INVALID_ACCOUNT_ID))));
    }

    @HapiTest
    @DisplayName("a top-level HAPI ContractUpdate still may not stake an immutable contract")
    final Stream<DynamicTest> hapiContractUpdateStillRejectsAnImmutableContract() {
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC632_CONTRACT),
                contractCreate(HRC632_CONTRACT).omitAdminKey(),
                contractUpdate(HRC632_CONTRACT)
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
                uploadInitCode(HRC632_CONTRACT),
                contractCreate(HRC632_CONTRACT).omitAdminKey(),
                getContractInfo(HRC632_CONTRACT).exposingEvmAddress(cb -> contractAddress.set(asHeadlongAddress(cb))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // No translator matches, so 0x16a returns empty output and the Solidity wrapper's
                        // abi.decode of an empty buffer reverts
                        contractCall(HRC632_CONTRACT, "stakeToNodeCall", contractAddress.get(), NODE_ID)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                getContractInfo(HRC632_CONTRACT).has(contractWith().noStakingNodeId()));
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hip1522;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.DELEGATE_CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.emptyChildRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordedChildBodyWithId;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.streamMustIncludePassFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlocks;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.idAsHeadlongAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_STAKING_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MODIFYING_IMMUTABLE_CONTRACT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SELF_STAKING_IS_NOT_ALLOWED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoUpdateTransactionBody;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Tests the account staking-configuration functions added to the Hedera Account Service by
 * <a href="https://github.com/hiero-ledger/hiero-improvement-proposals/blob/main/HIP/hip-1522.md">HIP-1522</a>.
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
    private static final String CODE_DELEGATIONS_FLAG = "contracts.codeDelegations.enabled";
    private static final String SELECTOR_COLLISION = "StakingSelectorCollision";
    /** What every StakingSelectorCollision function returns; a value HAS never would. */
    private static final long DELEGATE_MARKER = 4242L;

    private static final String HRC1522_CONTRACT = "HRC1522Contract";
    private static final String IHRC632 = "IHRC632";
    private static final String ACCOUNT = "account";
    private static final String OTHER = "other";
    private static final String DELEGATING = "delegating";
    private static final String ALIASED = "aliased";
    private static final String ALIASED_KEY = "aliasedKey";
    private static final String DELEGATE_KEY = "delegateKey";
    private static final String ADMIN_KEY = "adminKey";

    /**
     * Deliberately non-zero. HAPI reports a cleared staking target as an unset {@code staked_id} oneof, so the
     * proto getter reads 0 — which makes {@code stakedNodeId(0)} and {@code noStakingNodeId()} the very same
     * assertion, and every before/after pair in this file blind. The CI network for both smart-contract HAPI
     * tasks runs 3 nodes, so node 1 exists.
     */
    private static final long NODE_ID = 1L;
    /** The staked_node_id sentinel meaning "no node"; what unstake() leaves behind. */
    private static final long SENTINEL_NODE_ID = -1L;
    /** A non-negative node id no test network has. */
    private static final long NO_SUCH_NODE_ID = 999L;

    private static final Address ZERO_ADDRESS = asHeadlongAddress(new byte[20]);
    /** A long-zero address far beyond any entity a test network creates. */
    private static final Address MISSING_ACCOUNT_ADDRESS =
            asHeadlongAddress(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x7f, -1, -1, -1});

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
    @DisplayName("a staking call externalizes a staking-only CryptoUpdate child record")
    final Stream<DynamicTest> stakingCallExternalizesAStakingOnlyCryptoUpdate() {
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                // The mirror node learns of the change only through this child, so pin its body exactly: a
                // CryptoUpdate (never a ContractUpdate) naming the contract and carrying the staking fields alone
                streamMustIncludePassFrom(
                        recordedChildBodyWithId("stakeSelfForRecord", 1, (spec, txn) -> {
                            final var contractId = spec.registry().getContractId(HRC1522_CONTRACT);
                            assertEquals(
                                    CryptoUpdateTransactionBody.newBuilder()
                                            .setAccountIDToUpdate(AccountID.newBuilder()
                                                    .setShardNum(contractId.getShardNum())
                                                    .setRealmNum(contractId.getRealmNum())
                                                    .setAccountNum(contractId.getContractNum()))
                                            .setStakedNodeId(NODE_ID)
                                            .setDeclineReward(BoolValue.of(true))
                                            .build(),
                                    txn.getCryptoUpdateAccount());
                        }),
                        Duration.ofSeconds(30)),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC1522_CONTRACT),
                contractCreate(HRC1522_CONTRACT).omitAdminKey(),
                // The stream watcher ignores a file that already exists when it starts, so let a couple of blocks
                // pass first; otherwise the call can land in a file the assertion never sees
                waitUntilNextBlocks(2).withBackgroundTraffic(true),
                contractCall(HRC1522_CONTRACT, "stakeSelfToNodeCall", NODE_ID, true)
                        .payingWith(ACCOUNT)
                        .gas(1_000_000)
                        .via("stakeSelfForRecord"),
                childRecordsCheck("stakeSelfForRecord", SUCCESS, recordWith().status(SUCCESS)),
                // A leaky spec runs alone, so nothing else would close the block holding the child
                waitUntilNextBlocks(2).withBackgroundTraffic(true));
    }

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("getStakingInfo externalizes no child record")
    final Stream<DynamicTest> getStakingInfoExternalizesNoChildRecord() {
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC1522_CONTRACT),
                contractCreate(HRC1522_CONTRACT).omitAdminKey(),
                contractCall(HRC1522_CONTRACT, "stakeSelfToNodeCall", NODE_ID, true)
                        .payingWith(ACCOUNT)
                        .gas(1_000_000),
                // getStakingInfo dispatches nothing, so per HIP-1522 it leaves no trace in the record stream -
                // neither when it succeeds nor when it names an account that does not exist
                contractCall(HRC1522_CONTRACT, "getOwnStakingInfoCall")
                        .payingWith(ACCOUNT)
                        .gas(1_000_000)
                        .via("readForRecord"),
                emptyChildRecordsCheck("readForRecord", SUCCESS),
                contractCall(HRC1522_CONTRACT, "getStakingInfoCall", MISSING_ACCOUNT_ADDRESS)
                        .payingWith(ACCOUNT)
                        .gas(1_000_000)
                        .via("missingReadForRecord"),
                emptyChildRecordsCheck("missingReadForRecord", SUCCESS));
    }

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("a contract with an admin key stakes itself without that key's signature")
    final Stream<DynamicTest> adminKeyedContractStakesItselfWithoutTheAdminKey() {
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(HRC1522_CONTRACT),
                contractCreate(HRC1522_CONTRACT).adminKey(ADMIN_KEY),
                getContractInfo(HRC1522_CONTRACT).has(contractWith().adminKey(ADMIN_KEY)),
                // HIP-1522: a contract is inherently authorized for its own account, whatever its Hedera key. Only
                // the payer signs here, so the admin key - the contract account's key - never does
                contractCall(HRC1522_CONTRACT, "stakeSelfToNodeCall", NODE_ID, true)
                        .payingWith(ACCOUNT)
                        .signedBy(ACCOUNT)
                        .gas(1_000_000)
                        .via("adminKeyedStakeSelf"),
                succeeded("adminKeyedStakeSelf", "stakeSelfToNodeCall"),
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

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("getStakingInfo answers an eth_call as well as a transaction")
    final Stream<DynamicTest> getStakingInfoAnswersAQuery() {
        final AtomicReference<Address> contractAddress = new AtomicReference<>();
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC1522_CONTRACT),
                contractCreate(HRC1522_CONTRACT).omitAdminKey(),
                getContractInfo(HRC1522_CONTRACT).exposingEvmAddress(cb -> contractAddress.set(asHeadlongAddress(cb))),
                contractCall(HRC1522_CONTRACT, "stakeSelfToNodeCall", NODE_ID, true)
                        .payingWith(ACCOUNT)
                        .gas(1_000_000),
                // A ContractCallLocal - what the JSON-RPC relay serves eth_call with - runs in query scope, where
                // there is no consensus time and the stores come from the query context rather than a handle
                // context; none of that is reached by a contract call made in a transaction
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCallLocal(HRC1522_CONTRACT, "getStakingInfoCall", contractAddress.get())
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .has(resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, "getStakingInfoCall", HRC1522_CONTRACT),
                                                stakingInfoWith(true, 0L, 0L, NODE_ID, ZERO_ADDRESS))))));
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
                        readsOwnStakingInfo("read1", false, SENTINEL_NODE_ID, ZERO_ADDRESS),
                        // 2. the node-id sentinel
                        stakeToNode(contractAddress.get(), "stake2"),
                        getContractInfo(HRC1522_CONTRACT).has(contractWith().stakedNodeId(NODE_ID)),
                        contractCall(HRC1522_CONTRACT, "stakeToNodeCall", contractAddress.get(), SENTINEL_NODE_ID)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("unstake2"),
                        succeeded("unstake2", "stakeToNodeCall"),
                        getContractInfo(HRC1522_CONTRACT).has(contractWith().noStakingNodeId()),
                        readsOwnStakingInfo("read2", false, SENTINEL_NODE_ID, ZERO_ADDRESS),
                        // 3. the zero address, which resolves to the 0.0.0 staked_account_id sentinel
                        stakeToNode(contractAddress.get(), "stake3"),
                        getContractInfo(HRC1522_CONTRACT).has(contractWith().stakedNodeId(NODE_ID)),
                        contractCall(HRC1522_CONTRACT, "stakeToAccountCall", contractAddress.get(), ZERO_ADDRESS)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("unstake3"),
                        succeeded("unstake3", "stakeToAccountCall"),
                        getContractInfo(HRC1522_CONTRACT)
                                .has(contractWith().noStakedAccountId().noStakingNodeId()),
                        // ...and getStakingInfo reports the cleared state as both sentinels, every time
                        readsOwnStakingInfo("read3", false, SENTINEL_NODE_ID, ZERO_ADDRESS))));
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

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("stakeToAccount naming the account itself returns SELF_STAKING_IS_NOT_ALLOWED")
    final Stream<DynamicTest> contractCannotStakeToItself() {
        final AtomicReference<Address> contractAddress = new AtomicReference<>();
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC1522_CONTRACT),
                contractCreate(HRC1522_CONTRACT).omitAdminKey(),
                getContractInfo(HRC1522_CONTRACT).exposingEvmAddress(cb -> contractAddress.set(asHeadlongAddress(cb))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // Stake first, so "nothing changed" below is a real observation
                        stakeToNode(contractAddress.get(), "stakeFirst"),
                        contractCall(
                                        HRC1522_CONTRACT,
                                        "stakeToAccountCall",
                                        contractAddress.get(),
                                        contractAddress.get())
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("selfStake"),
                        returned(
                                "selfStake",
                                "stakeToAccountCall",
                                HRC1522_CONTRACT,
                                SELF_STAKING_IS_NOT_ALLOWED.getNumber()))),
                getContractInfo(HRC1522_CONTRACT)
                        .has(contractWith().stakedNodeId(NODE_ID).noStakedAccountId()));
    }

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("an EOA cannot stake to itself through the IHRC632 facade")
    final Stream<DynamicTest> eoaCannotStakeToItselfThroughTheFacade() {
        final AtomicReference<AccountID> accountId = new AtomicReference<>();
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS).exposingCreatedIdTo(accountId::set),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCallWithFunctionAbi(
                                        String.valueOf(accountId.get().getAccountNum()),
                                        getABIFor(FUNCTION, "stakeToAccount", IHRC632),
                                        idAsHeadlongAddress(accountId.get()))
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("facadeSelfStake"),
                        returned(
                                "facadeSelfStake",
                                "stakeToAccount",
                                IHRC632,
                                SELF_STAKING_IS_NOT_ALLOWED.getNumber()))),
                getAccountInfo(ACCOUNT).has(accountWith().noStakedAccountId()));
    }

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("invalid staking ids and missing accounts return the HIP's response codes")
    final Stream<DynamicTest> invalidInputsReturnTheSpecifiedResponseCodes() {
        final AtomicReference<Address> contractAddress = new AtomicReference<>();
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                uploadInitCode(HRC1522_CONTRACT),
                contractCreate(HRC1522_CONTRACT).omitAdminKey(),
                getContractInfo(HRC1522_CONTRACT).exposingEvmAddress(cb -> contractAddress.set(asHeadlongAddress(cb))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // Stake first, so "nothing changed" below is a real observation
                        stakeToNode(contractAddress.get(), "stakeFirst"),
                        // A node id that names no node
                        contractCall(HRC1522_CONTRACT, "stakeToNodeCall", contractAddress.get(), NO_SUCH_NODE_ID)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("noSuchNode"),
                        returned("noSuchNode", "stakeToNodeCall", HRC1522_CONTRACT, INVALID_STAKING_ID.getNumber()),
                        // -1 is the only sentinel; any other negative id is invalid, not a request to clear
                        contractCall(HRC1522_CONTRACT, "stakeToNodeCall", contractAddress.get(), -2L)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("minusTwo"),
                        returned("minusTwo", "stakeToNodeCall", HRC1522_CONTRACT, INVALID_STAKING_ID.getNumber()),
                        // A staked-to account that does not exist
                        contractCall(
                                        HRC1522_CONTRACT,
                                        "stakeToAccountCall",
                                        contractAddress.get(),
                                        MISSING_ACCOUNT_ADDRESS)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("noSuchStakedTo"),
                        returned(
                                "noSuchStakedTo",
                                "stakeToAccountCall",
                                HRC1522_CONTRACT,
                                INVALID_STAKING_ID.getNumber()),
                        // A target account that does not exist
                        contractCall(HRC1522_CONTRACT, "stakeToNodeCall", MISSING_ACCOUNT_ADDRESS, NODE_ID)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("noSuchTarget"),
                        returned("noSuchTarget", "stakeToNodeCall", HRC1522_CONTRACT, INVALID_ACCOUNT_ID.getNumber()),
                        // ...and reading one returns INVALID_ACCOUNT_ID with a zeroed struct, rather than reverting
                        contractCall(HRC1522_CONTRACT, "getStakingInfoCall", MISSING_ACCOUNT_ADDRESS)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("readNoSuchAccount"),
                        getTxnRecord("readNoSuchAccount")
                                .hasPriority(recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .resultThruAbi(
                                                        getABIFor(FUNCTION, "getStakingInfoCall", HRC1522_CONTRACT),
                                                        isLiteralResult(new Object[] {
                                                            (long) INVALID_ACCOUNT_ID.getNumber(),
                                                            Tuple.of(false, 0L, 0L, 0L, SENTINEL_NODE_ID, ZERO_ADDRESS)
                                                        })))))),
                // None of the rejected calls disturbed the staking already in place
                getContractInfo(HRC1522_CONTRACT).has(contractWith().stakedNodeId(NODE_ID)));
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
                                .has(contractWith().stakedAccountId(OTHER).noStakingNodeId()),
                        // An account with no alias reads back in its long-zero form, with no node target
                        readsOwnStakingInfo(
                                "readAccountTarget", false, SENTINEL_NODE_ID, idAsHeadlongAddress(otherId.get())))));
    }

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("stakeToAccount accepts both the alias and the long-zero form of an aliased account")
    final Stream<DynamicTest> stakeToAccountAcceptsEitherFormOfAnAliasedAccount() {
        final AtomicReference<Address> contractAddress = new AtomicReference<>();
        final AtomicReference<Address> aliasAddress = new AtomicReference<>();
        final AtomicReference<Address> longZeroAddress = new AtomicReference<>();
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                newKeyNamed(ALIASED_KEY).shape(SECP_256K1_SHAPE),
                withOpContext((spec, opLog) -> {
                    final var key = spec.registry().getKey(ALIASED_KEY);
                    final var evmAddress =
                            recoverAddressFromPubKey(key.getECDSASecp256K1().toByteArray());
                    aliasAddress.set(asHeadlongAddress(evmAddress));
                    allRunFor(
                            spec,
                            cryptoCreate(ALIASED)
                                    .key(ALIASED_KEY)
                                    .alias(ByteString.copyFrom(evmAddress))
                                    .exposingCreatedIdTo(id -> longZeroAddress.set(idAsHeadlongAddress(id))));
                }),
                uploadInitCode(HRC1522_CONTRACT),
                contractCreate(HRC1522_CONTRACT).omitAdminKey(),
                getContractInfo(HRC1522_CONTRACT).exposingEvmAddress(cb -> contractAddress.set(asHeadlongAddress(cb))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // 1. The long-zero form, which is what getHederaAccountNumAlias hands back. It is a
                        // non-canonical reference, but HIP-1522 requires either form to name the same account
                        contractCall(
                                        HRC1522_CONTRACT,
                                        "stakeToAccountCall",
                                        contractAddress.get(),
                                        longZeroAddress.get())
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("viaLongZero"),
                        succeeded("viaLongZero", "stakeToAccountCall"),
                        getContractInfo(HRC1522_CONTRACT)
                                .has(contractWith().stakedAccountId(ALIASED).noStakingNodeId()),
                        // ...and it reads back in the account's priority form, its alias
                        readsOwnStakingInfo("readViaLongZero", false, SENTINEL_NODE_ID, aliasAddress.get()),
                        // 2. The alias form names the very same account, so the value read back round-trips
                        contractCall(HRC1522_CONTRACT, "unstakeCall", contractAddress.get())
                                .payingWith(ACCOUNT)
                                .gas(1_000_000),
                        getContractInfo(HRC1522_CONTRACT).has(contractWith().noStakedAccountId()),
                        contractCall(HRC1522_CONTRACT, "stakeToAccountCall", contractAddress.get(), aliasAddress.get())
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("viaAlias"),
                        succeeded("viaAlias", "stakeToAccountCall"),
                        getContractInfo(HRC1522_CONTRACT)
                                .has(contractWith().stakedAccountId(ALIASED).noStakingNodeId()),
                        readsOwnStakingInfo("readViaAlias", false, SENTINEL_NODE_ID, aliasAddress.get()))));
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

    @LeakyHapiTest(overrides = {STAKING_FLAG})
    @DisplayName("a contract stakes another account whose key delegates to that contract")
    final Stream<DynamicTest> crossAccountCallAuthorizedByTheSecurityModelSucceeds() {
        final AtomicReference<Address> otherAddress = new AtomicReference<>();
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(OTHER).exposingCreatedIdTo(id -> otherAddress.set(idAsHeadlongAddress(id))),
                uploadInitCode(HRC1522_CONTRACT),
                contractCreate(HRC1522_CONTRACT).omitAdminKey(),
                // Under the smart-contract security model a contract is authorized for another account when that
                // account's key activates for the calling contract; the mirror image of the rejected case above
                newKeyNamed(DELEGATE_KEY)
                        .shape(threshOf(1, SIMPLE, DELEGATE_CONTRACT).signedWith(sigs(ON, HRC1522_CONTRACT))),
                cryptoUpdate(OTHER).key(DELEGATE_KEY),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(HRC1522_CONTRACT, "stakeToNodeCall", otherAddress.get(), NODE_ID)
                                .payingWith(ACCOUNT)
                                .gas(1_000_000)
                                .via("delegatedCrossAccount"),
                        succeeded("delegatedCrossAccount", "stakeToNodeCall"))),
                getAccountInfo(OTHER).has(accountWith().stakedNodeId(NODE_ID)));
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
    @DisplayName("an EOA uses every remaining IHRC632 facade form on its own address")
    final Stream<DynamicTest> eoaUsesTheRemainingFacadeForms() {
        final AtomicReference<AccountID> accountId = new AtomicReference<>();
        final AtomicReference<AccountID> otherId = new AtomicReference<>();
        return hapiTest(
                overriding(STAKING_FLAG, "true"),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS).exposingCreatedIdTo(accountId::set),
                cryptoCreate(OTHER).exposingCreatedIdTo(otherId::set),
                withOpContext((spec, opLog) -> {
                    final var self = String.valueOf(accountId.get().getAccountNum());
                    allRunFor(
                            spec,
                            // stakeToNodeAndDeclineReward(int64,bool)
                            contractCallWithFunctionAbi(
                                            self,
                                            getABIFor(FUNCTION, "stakeToNodeAndDeclineReward", IHRC632),
                                            NODE_ID,
                                            true)
                                    .payingWith(ACCOUNT)
                                    .gas(1_000_000)
                                    .via("facadeCombined"),
                            returned("facadeCombined", "stakeToNodeAndDeclineReward", IHRC632, SUCCESS.getNumber()),
                            getAccountInfo(ACCOUNT)
                                    .has(accountWith().stakedNodeId(NODE_ID).isDeclinedReward(true)),
                            // getStakingInfo(). (A HAPI ContractCallLocal cannot target an EOA - it fails precheck with
                            // INVALID_CONTRACT_ID - so the facade's read is only reachable here from a transaction.)
                            contractCallWithFunctionAbi(self, getABIFor(FUNCTION, "getStakingInfo", IHRC632))
                                    .payingWith(ACCOUNT)
                                    .gas(1_000_000)
                                    .via("facadeRead"),
                            getTxnRecord("facadeRead")
                                    .hasPriority(recordWith()
                                            .status(SUCCESS)
                                            .contractCallResult(resultWith()
                                                    .resultThruAbi(
                                                            getABIFor(FUNCTION, "getStakingInfo", IHRC632),
                                                            stakingInfoWith(true, 0L, 0L, NODE_ID, ZERO_ADDRESS)))),
                            // stakeToAccount(address)
                            contractCallWithFunctionAbi(
                                            self,
                                            getABIFor(FUNCTION, "stakeToAccount", IHRC632),
                                            idAsHeadlongAddress(otherId.get()))
                                    .payingWith(ACCOUNT)
                                    .gas(1_000_000)
                                    .via("facadeStakeToAccount"),
                            returned("facadeStakeToAccount", "stakeToAccount", IHRC632, SUCCESS.getNumber()),
                            getAccountInfo(ACCOUNT)
                                    .has(accountWith()
                                            .stakedAccountId(otherId.get().getAccountNum())
                                            .noStakingNodeId()),
                            // unstake()
                            contractCallWithFunctionAbi(self, getABIFor(FUNCTION, "unstake", IHRC632))
                                    .payingWith(ACCOUNT)
                                    .gas(1_000_000)
                                    .via("facadeUnstake"),
                            returned("facadeUnstake", "unstake", IHRC632, SUCCESS.getNumber()),
                            getAccountInfo(ACCOUNT)
                                    .has(accountWith().noStakedAccountId().noStakingNodeId()));
                }));
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

    @LeakyHapiTest(overrides = {STAKING_FLAG, CODE_DELEGATIONS_FLAG})
    @DisplayName("with the flag off, an EIP-7702 delegate's same-named functions are not diverted to HAS")
    final Stream<DynamicTest> flagOffLeavesDelegatedAccountsToTheirDelegate() {
        final AtomicReference<Address> delegateAddress = new AtomicReference<>();
        final AtomicReference<AccountID> delegatingId = new AtomicReference<>();
        return hapiTest(
                overriding(CODE_DELEGATIONS_FLAG, "true"),
                overriding(STAKING_FLAG, "false"),
                uploadInitCode(SELECTOR_COLLISION),
                contractCreate(SELECTOR_COLLISION).exposingAddressTo(delegateAddress::set),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        cryptoCreate(DELEGATING)
                                .balance(100 * ONE_HUNDRED_HBARS)
                                .delegationAddress(ByteString.fromHex(
                                        delegateAddress.get().toString().substring(2)))
                                .exposingCreatedIdTo(delegatingId::set))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // The HAS redirect takes priority over a code delegation, so were the facade selectors
                        // ungated this unstake() would reach HAS rather than the delegate the account chose
                        delegatedUnstake(delegatingId.get(), "flagOff"),
                        returned("flagOff", "unstake", SELECTOR_COLLISION, DELEGATE_MARKER),
                        // With the feature on, the facade takes priority, exactly as it already does for HIP-906
                        overriding(STAKING_FLAG, "true"),
                        delegatedUnstake(delegatingId.get(), "flagOn"),
                        returned("flagOn", "unstake", IHRC632, SUCCESS.getNumber()))));
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
     * Calls {@code getOwnStakingInfoCall()} and asserts the target fields of the struct it returns; the
     * contract has nothing staked to it and, unless staked to a node, no pending reward.
     */
    private static SpecOperation readsOwnStakingInfo(
            final String txn, final boolean declineReward, final long stakedNodeId, final Address stakedAccountId) {
        return blockingOrder(
                contractCall(HRC1522_CONTRACT, "getOwnStakingInfoCall")
                        .payingWith(ACCOUNT)
                        .gas(1_000_000)
                        .via(txn),
                getTxnRecord(txn)
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, "getOwnStakingInfoCall", HRC1522_CONTRACT),
                                                stakingInfoWith(
                                                        declineReward, 0L, 0L, stakedNodeId, stakedAccountId)))));
    }

    /** Calls {@code unstake()} on the given account, paid and signed by that account. */
    private static SpecOperation delegatedUnstake(final AccountID account, final String txn) {
        return contractCallWithFunctionAbi(
                        String.valueOf(account.getAccountNum()), getABIFor(FUNCTION, "unstake", SELECTOR_COLLISION))
                .payingWith(DELEGATING)
                .gas(1_000_000)
                .via(txn);
    }

    /** Asserts the named call returned the given {@code int64}, decoded with the given contract's ABI. */
    private static SpecOperation returned(
            final String txn, final String function, final String abiContract, final long expected) {
        return getTxnRecord(txn)
                .hasPriority(recordWith()
                        .status(SUCCESS)
                        .contractCallResult(resultWith()
                                .resultThruAbi(
                                        getABIFor(FUNCTION, function, abiContract),
                                        isLiteralResult(new Object[] {expected}))));
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

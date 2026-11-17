// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.protoToPbj;
import static com.hedera.node.app.service.token.HookDispatchUtils.HTS_HOOKS_CONTRACT_NUM;
import static com.hedera.node.config.types.StreamMode.BOTH;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension.SHARED_NETWORK;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.AutoAssocAsserts.accountTokenPairs;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomAccount.INITIAL_BALANCE;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord.nonStakingRecordsFrom;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.accountEvmHookStore;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdateAliased;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.streamMustIncludePassWithReplayFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.records.SelfDestructRecordsSuite.deletionRecords;
import static com.hedera.services.bdd.suites.contract.records.SelfDestructRecordsSuite.startNewRecordFile;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.AUTO_MEMO;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.LAZY_MEMO;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.createHollowAccountFrom;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.OWNER;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.PAYER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenSupplyType.FINITE;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.esaulpaugh.headlong.abi.Single;
import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.google.protobuf.ByteString;
import com.hedera.hapi.block.stream.trace.ContractSlotUsage;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.junit.support.translators.RoleFreeBlockUnitSplit;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hedera.services.bdd.spec.utilops.streams.assertions.BlockStreamAssertion;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.EvmHookCall;
import com.hederahashgraph.api.proto.java.HookCall;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.stream.Stream;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

// This class uses class-scoped hook overrides and must not share a concurrent subprocess network.
@HapiTestLifecycle
@OrderedInIsolation
@SuppressWarnings({"rawtypes", "unchecked"})
public class Hip1195StreamParityTest {
    public static final String HOOK_CONTRACT_NUM = "365";

    private static final TupleType SET_AND_PASS_ARGS = TupleType.parse("(uint32,address)");

    @Contract(contract = "Multipurpose", creationGas = 500_000L)
    static SpecContract MULTIPURPOSE;

    @Contract(contract = "SetAndPassHook", creationGas = 1_000_000L)
    static SpecContract SET_AND_PASS_HOOK;

    @Contract(contract = "ThreePassesHook", creationGas = 1_000_000L)
    static SpecContract THREE_PASSES_HOOK;

    @Contract(contract = "TruePrePostHook", creationGas = 5_000_000)
    static SpecContract TRUE_PRE_POST_ALLOWANCE_HOOK;

    @Contract(contract = "CreateOpHook", creationGas = 5_000_000)
    static SpecContract CREATE_OP_HOOK;

    @Contract(contract = "ManagedTransferCap", creationGas = 5_000_000)
    static SpecContract MANAGED_TRANSFER_CAP_HOOK;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
        testLifecycle.doAdhoc(MULTIPURPOSE.getInfo());
        testLifecycle.doAdhoc(CREATE_OP_HOOK.getInfo());
        testLifecycle.doAdhoc(MANAGED_TRANSFER_CAP_HOOK.getInfo());
        testLifecycle.doAdhoc(SET_AND_PASS_HOOK.getInfo());
        testLifecycle.doAdhoc(THREE_PASSES_HOOK.getInfo());
        testLifecycle.doAdhoc(TRUE_PRE_POST_ALLOWANCE_HOOK.getInfo());
    }

    @HapiTest
    final Stream<DynamicTest> hookChildSelfDestructExternalizesDeletion() {
        final var hook = "SelfDestructChildHook";
        final var transfer = "hookWithSelfDestruct";
        final var finished = "finished";
        return hapiTest(
                deletionRecords(transfer, finished, 1, OWNER, null, SUCCESS),
                startNewRecordFile(),
                uploadInitCode(hook),
                contractCreate(hook).gas(1_000_000L),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).withHook(accountAllowanceHook(1L, hook)),
                cryptoTransfer(movingHbar(1).between(OWNER, GENESIS))
                        .withPreHookFor(OWNER, 1L, 1_000_000L, "")
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .via(transfer),
                withOpContext((spec, log) -> {
                    final var query = getTxnRecord(transfer)
                            .hasPriority(recordWith().status(SUCCESS))
                            .hasNonStakingChildRecordCount(3);
                    allRunFor(spec, query);
                    final var records = nonStakingRecordsFrom(query.getChildRecords());
                    final var createdId = records.get(1).getReceipt().getContractID();
                    assertEquals(SUCCESS, records.getFirst().getReceipt().getStatus());
                    assertEquals(createdId, records.getLast().getReceipt().getContractID());
                    spec.registry().saveContractId("deletedHookChild", createdId);
                    allRunFor(
                            spec,
                            getContractInfo("deletedHookChild")
                                    .has(contractWith().isDeleted()),
                            getContractInfo(hook).has(contractWith().isNotDeleted()));
                }),
                getAccountInfo(OWNER).has(accountWith().balance(ONE_HUNDRED_HBARS - 1)),
                cryptoTransfer(movingHbar(1).between(DEFAULT_PAYER, PAYER)).via(finished));
    }

    @HapiTest
    final Stream<DynamicTest> hookChildCreationPassesParity(
            @FungibleToken SpecFungibleToken aToken, @NonFungibleToken(numPreMints = 1) SpecNonFungibleToken bToken) {
        return hapiTest(
                aToken.getInfo(),
                bToken.getInfo(),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(OWNER)
                        .maxAutomaticTokenAssociations(2)
                        .withHooks(accountAllowanceHook(210L, CREATE_OP_HOOK.name())),
                cryptoTransfer(
                        moving(1, aToken.name()).between(aToken.treasury().name(), OWNER),
                        movingUnique(bToken.name(), 1L)
                                .between(bToken.treasury().name(), OWNER)),
                cryptoTransfer(
                                movingHbar(1).between(OWNER, GENESIS),
                                moving(1, aToken.name())
                                        .between(OWNER, aToken.treasury().name()),
                                movingUnique(bToken.name(), 1L)
                                        .between(OWNER, bToken.treasury().name()))
                        .withPreHookFor(OWNER, 210L, 5_000_000L, "")
                        .withNftSenderPreHookFor(OWNER, 210L, 5_000_000L, "")
                        .payingWith(PAYER)
                        .signedBy(PAYER),
                getAccountInfo(OWNER).exposingEthereumNonceTo(nonce -> assertEquals(3, nonce)));
    }

    @HapiTest
    final Stream<DynamicTest> isolatedExecutionWithNonHookStorageSideEffectsPassesParity() {
        return hapiTest(
                cryptoCreate("party").withHooks(accountAllowanceHook(1L, SET_AND_PASS_HOOK.name())),
                cryptoCreate("counterparty"),
                cryptoTransfer((spec, b) -> b.setTransfers(TransferList.newBuilder()
                                .addAccountAmounts(AccountAmount.newBuilder()
                                        .setAccountID(spec.registry().getAccountID("party"))
                                        .setAmount(-123L)
                                        .setPrePostTxAllowanceHook(HookCall.newBuilder()
                                                .setHookId(1L)
                                                .setEvmHookCall(EvmHookCall.newBuilder()
                                                        .setGasLimit(42_000L)
                                                        .setData(ByteString.copyFrom(SET_AND_PASS_ARGS.encode(Tuple.of(
                                                                666L,
                                                                MULTIPURPOSE.addressOn(
                                                                        spec.targetNetworkOrThrow()))))))))
                                .addAccountAmounts(AccountAmount.newBuilder()
                                        .setAccountID(spec.registry().getAccountID("counterparty"))
                                        .setAmount(+123L))))
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> isolatedExecutionsWithIdenticalHookStorageSideEffectsPassParity() {
        final var args = TupleType.parse("(uint32)");
        final LongFunction<HapiCryptoTransfer> attempt =
                n -> cryptoTransfer((spec, b) -> b.setTransfers(TransferList.newBuilder()
                                .addAccountAmounts(AccountAmount.newBuilder()
                                        .setAccountID(spec.registry().getAccountID("party"))
                                        .setAmount(-123L)
                                        .setPreTxAllowanceHook(HookCall.newBuilder()
                                                .setHookId(1L)
                                                .setEvmHookCall(EvmHookCall.newBuilder()
                                                        .setGasLimit(42_000L)
                                                        .setData(ByteString.copyFrom(args.encode(Single.of(n)))))))
                                .addAccountAmounts(AccountAmount.newBuilder()
                                        .setAccountID(spec.registry().getAccountID("counterparty"))
                                        .setAmount(+123L))))
                        .signedBy(DEFAULT_PAYER);
        return hapiTest(
                cryptoCreate("party").withHooks(accountAllowanceHook(1L, THREE_PASSES_HOOK.name())),
                cryptoCreate("counterparty"),
                sourcing(() -> attempt.apply(1L)),
                sourcing(() -> attempt.apply(2L)),
                sourcing(() -> attempt.apply(3L)),
                sourcing(() -> attempt.apply(4L).hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK)));
    }

    /**
     * One pre-only and one pre-post hook invocation execute two different hooks that both write slot zero of their
     * own storage. The implicit list of written keys does not distinguish hooks, so all three executions must trace
     * their writes explicitly.
     */
    @HapiTest
    final Stream<DynamicTest> preOnlyAndPrePostHookExecutionsTraceExplicitWrites() {
        // Storage traces only go to the block stream
        assumeFalse(targetStreamMode() == RECORDS, "No block stream to check when streamMode=RECORDS");
        final var transfer = "preOnlyAndPrePostTransfer";
        final var args = TupleType.parse("(uint32)");
        return hapiTest(
                cryptoCreate("party").withHooks(accountAllowanceHook(1L, THREE_PASSES_HOOK.name())),
                cryptoCreate("counterparty").withHooks(accountAllowanceHook(2L, SET_AND_PASS_HOOK.name())),
                cryptoTransfer((spec, b) -> b.setTransfers(TransferList.newBuilder()
                                .addAccountAmounts(AccountAmount.newBuilder()
                                        .setAccountID(spec.registry().getAccountID("party"))
                                        .setAmount(-123L)
                                        .setPreTxAllowanceHook(HookCall.newBuilder()
                                                .setHookId(1L)
                                                .setEvmHookCall(EvmHookCall.newBuilder()
                                                        .setGasLimit(42_000L)
                                                        .setData(ByteString.copyFrom(args.encode(Single.of(1L)))))))
                                .addAccountAmounts(AccountAmount.newBuilder()
                                        .setAccountID(spec.registry().getAccountID("counterparty"))
                                        .setAmount(+123L)
                                        .setPrePostTxAllowanceHook(HookCall.newBuilder()
                                                .setHookId(2L)
                                                .setEvmHookCall(EvmHookCall.newBuilder()
                                                        .setGasLimit(64_000L)
                                                        .setData(ByteString.copyFrom(SET_AND_PASS_ARGS.encode(Tuple.of(
                                                                2L,
                                                                MULTIPURPOSE.addressOn(
                                                                        spec.targetNetworkOrThrow()))))))))))
                        .signedBy(DEFAULT_PAYER)
                        .via(transfer),
                streamMustIncludePassWithReplayFrom(
                        spec -> hookExecutionsTraceExplicitWrites(spec, transfer, 3), Duration.ofSeconds(30)));
    }

    /**
     * A single pre-post hook invocation is two executions of the same hook; here the first only reads the slot that
     * the second writes. The implicit list of written keys does not distinguish executions, so both must trace
     * their writes explicitly.
     */
    @HapiTest
    final Stream<DynamicTest> prePostHookExecutionsTraceExplicitWrites() {
        // Storage traces only go to the block stream
        assumeFalse(targetStreamMode() == RECORDS, "No block stream to check when streamMode=RECORDS");
        final var transfer = "prePostTransfer";
        return hapiTest(
                cryptoCreate("party").withHooks(accountAllowanceHook(1L, MANAGED_TRANSFER_CAP_HOOK.name())),
                // Slot zero is the remaining cap on credits (here 500), which the hook only reads pre-transaction,
                // and reduces by the credit post-transaction
                accountEvmHookStore("party", 1L).putSlot(Bytes.EMPTY, Bytes.fromHex("01f4")),
                cryptoTransfer(movingHbar(200L).between(DEFAULT_PAYER, "party"))
                        .withPrePostHookFor("party", 1L, 100_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .via(transfer),
                streamMustIncludePassWithReplayFrom(
                        spec -> hookExecutionsTraceExplicitWrites(spec, transfer, 2), Duration.ofSeconds(30)));
    }

    @HapiTest
    final Stream<DynamicTest> repeatedExecutionsWithNonHookStorageSideEffectsPassParity(
            @FungibleToken SpecFungibleToken aToken, @NonFungibleToken(numPreMints = 1) SpecNonFungibleToken bToken) {
        return hapiTest(
                aToken.getInfo(),
                bToken.getInfo(),
                cryptoCreate("party")
                        .maxAutomaticTokenAssociations(2)
                        .withHooks(
                                accountAllowanceHook(1L, SET_AND_PASS_HOOK.name()),
                                accountAllowanceHook(2L, SET_AND_PASS_HOOK.name())),
                cryptoCreate("counterparty")
                        .maxAutomaticTokenAssociations(2)
                        .withHooks(
                                accountAllowanceHook(3L, SET_AND_PASS_HOOK.name()),
                                accountAllowanceHook(4L, SET_AND_PASS_HOOK.name())),
                cryptoTransfer(
                        moving(1, aToken.name()).between(aToken.treasury().name(), "party"),
                        movingUnique(bToken.name(), 1L)
                                .between(bToken.treasury().name(), "counterparty")),
                // A complex multiparty transfer where all four hooks are invoked twice each,
                // the odd-numbered ids by being used in pre/post hooks and the even-numbered
                // ids by being used in two separate pre hook executions
                cryptoTransfer((spec, b) -> {
                            final var partyId = spec.registry().getAccountID("party");
                            final var counterpartyId = spec.registry().getAccountID("counterparty");
                            final var targetAddress = MULTIPURPOSE.addressOn(spec.targetNetworkOrThrow());
                            final long gasLimit = 64_000L;
                            // First the pre/post hooks in the HBAR transfer list
                            b.setTransfers(TransferList.newBuilder()
                                    .addAccountAmounts(AccountAmount.newBuilder()
                                            .setAccountID(partyId)
                                            .setAmount(-123L)
                                            .setPrePostTxAllowanceHook(HookCall.newBuilder()
                                                    .setHookId(1L)
                                                    .setEvmHookCall(EvmHookCall.newBuilder()
                                                            .setGasLimit(gasLimit)
                                                            .setData(ByteString.copyFrom(SET_AND_PASS_ARGS.encode(
                                                                    Tuple.of(1L, targetAddress)))))))
                                    .addAccountAmounts(AccountAmount.newBuilder()
                                            .setAccountID(counterpartyId)
                                            .setAmount(+123L)
                                            .setPrePostTxAllowanceHook(HookCall.newBuilder()
                                                    .setHookId(3L)
                                                    .setEvmHookCall(EvmHookCall.newBuilder()
                                                            .setGasLimit(gasLimit)
                                                            .setData(ByteString.copyFrom(SET_AND_PASS_ARGS.encode(
                                                                    Tuple.of(3L, targetAddress))))))));
                            // Then the first calls to the pre hooks in the fungible token transfer
                            b.addTokenTransfers(TokenTransferList.newBuilder()
                                    .setToken(spec.registry().getTokenID(aToken.name()))
                                    .addTransfers(AccountAmount.newBuilder()
                                            .setAccountID(partyId)
                                            .setAmount(-1L)
                                            .setPreTxAllowanceHook(HookCall.newBuilder()
                                                    .setHookId(2L)
                                                    .setEvmHookCall(EvmHookCall.newBuilder()
                                                            .setGasLimit(gasLimit)
                                                            .setData(ByteString.copyFrom(SET_AND_PASS_ARGS.encode(
                                                                    Tuple.of(2L, targetAddress)))))))
                                    .addTransfers(AccountAmount.newBuilder()
                                            .setAccountID(counterpartyId)
                                            .setAmount(+1L)
                                            .setPreTxAllowanceHook(HookCall.newBuilder()
                                                    .setHookId(4L)
                                                    .setEvmHookCall(EvmHookCall.newBuilder()
                                                            .setGasLimit(gasLimit)
                                                            .setData(ByteString.copyFrom(SET_AND_PASS_ARGS.encode(
                                                                    Tuple.of(4L, targetAddress))))))));
                            // And finally the repeated calls to the pre hooks in the NFT transfer
                            b.addTokenTransfers(TokenTransferList.newBuilder()
                                    .setToken(spec.registry().getTokenID(bToken.name()))
                                    .addNftTransfers(NftTransfer.newBuilder()
                                            .setSerialNumber(1L)
                                            .setSenderAccountID(counterpartyId)
                                            .setPreTxSenderAllowanceHook(HookCall.newBuilder()
                                                    .setHookId(4L)
                                                    .setEvmHookCall(EvmHookCall.newBuilder()
                                                            .setGasLimit(gasLimit)
                                                            .setData(ByteString.copyFrom(SET_AND_PASS_ARGS.encode(
                                                                    Tuple.of(12L, targetAddress))))))
                                            .setReceiverAccountID(partyId)
                                            .setPreTxReceiverAllowanceHook(HookCall.newBuilder()
                                                    .setHookId(2L)
                                                    .setEvmHookCall(EvmHookCall.newBuilder()
                                                            .setGasLimit(gasLimit)
                                                            .setData(ByteString.copyFrom(SET_AND_PASS_ARGS.encode(
                                                                    Tuple.of(14L, targetAddress))))))));
                        })
                        .via("complexTransfer"),
                getTxnRecord("complexTransfer").hasNonStakingChildRecordCount(8).andAllChildRecords());
    }

    @HapiTest
    final Stream<DynamicTest> hookExecutionsWithAutoCreations() {
        final var initialTokenSupply = 1000;
        return hapiTest(
                newKeyNamed("alias"),
                cryptoCreate(TOKEN_TREASURY).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate("civilian")
                        .balance(ONE_HUNDRED_HBARS)
                        .maxAutomaticTokenAssociations(2)
                        .withHook(accountAllowanceHook(1L, TRUE_PRE_POST_ALLOWANCE_HOOK.name())),
                tokenCreate("tokenA")
                        .tokenType(FUNGIBLE_COMMON)
                        .supplyType(FINITE)
                        .initialSupply(initialTokenSupply)
                        .maxSupply(10L * initialTokenSupply)
                        .treasury(TOKEN_TREASURY)
                        .via("tokenCreation"),
                getTxnRecord("tokenCreation").hasNewTokenAssociation("tokenA", TOKEN_TREASURY),
                tokenAssociate("civilian", "tokenA"),
                cryptoTransfer(moving(10, "tokenA").between(TOKEN_TREASURY, "civilian")),
                getAccountInfo("civilian").hasToken(relationshipWith("tokenA").balance(10)),
                cryptoTransfer(
                                movingHbar(10L).between("civilian", "alias"),
                                moving(1, "tokenA").between("civilian", "alias"))
                        .withPrePostHookFor("civilian", 1L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER, "civilian")
                        .via("transfer"),
                getTxnRecord("transfer")
                        .andAllChildRecords()
                        .hasNonStakingChildRecordCount(5)
                        .hasChildRecords(
                                recordWith().status(SUCCESS).memo(AUTO_MEMO),
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith().contract(HOOK_CONTRACT_NUM)),
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith().contract(HOOK_CONTRACT_NUM)),
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith().contract(HOOK_CONTRACT_NUM)),
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith().contract(HOOK_CONTRACT_NUM)))
                        .logged(),
                getAliasedAccountInfo("alias").has(accountWith().balance(10L)).hasToken(relationshipWith("tokenA")),
                cryptoTransfer(
                                movingHbar(10L).between("civilian", "alias"),
                                moving(1, "tokenA").between("civilian", "alias"))
                        .withPrePostHookFor("civilian", 1L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER, "civilian")
                        .via("aliasTransfer"),
                getTxnRecord("transfer").andAllChildRecords().logged(),
                getAliasedAccountInfo("alias").has(accountWith().balance(20L)));
    }

    @HapiTest
    final Stream<DynamicTest> hookExecutionsWithAutoAssociations() {
        final var beneficiary = "beneficiary";
        final var uniqueToken = "uniqueToken";
        final var fungibleToken = "fungibleToken";
        final var multiPurpose = "multiPurpose";
        final var transferTxn = "transferTxn";

        return hapiTest(
                newKeyNamed(multiPurpose),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(fungibleToken)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1_000L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(uniqueToken)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(multiPurpose)
                        .initialSupply(0L)
                        .treasury(TOKEN_TREASURY),
                mintToken(uniqueToken, List.of(copyFromUtf8("ONE"), copyFromUtf8("TWO"))),
                cryptoCreate(beneficiary)
                        .withHook(accountAllowanceHook(1L, TRUE_PRE_POST_ALLOWANCE_HOOK.name()))
                        .balance(ONE_HUNDRED_HBARS)
                        .receiverSigRequired(true)
                        .maxAutomaticTokenAssociations(2),
                getAccountInfo(beneficiary).savingSnapshot(beneficiary),
                cryptoTransfer(
                                movingUnique(uniqueToken, 1L).between(TOKEN_TREASURY, beneficiary),
                                moving(500, fungibleToken).between(TOKEN_TREASURY, beneficiary))
                        .withPrePostHookFor(beneficiary, 1L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(
                                movingUnique(uniqueToken, 1L).between(TOKEN_TREASURY, beneficiary),
                                moving(500, fungibleToken).between(TOKEN_TREASURY, beneficiary))
                        .withPrePostHookFor(beneficiary, 1L, 25_000L, "")
                        .withNftReceiverPrePostHookFor(beneficiary, 1L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER, TOKEN_TREASURY)
                        .via(transferTxn),
                getTxnRecord(transferTxn)
                        .hasPriority(recordWith()
                                .autoAssociated(accountTokenPairs(List.of(
                                        Pair.of(beneficiary, fungibleToken), Pair.of(beneficiary, uniqueToken)))))
                        .andAllChildRecords()
                        .hasNonStakingChildRecordCount(4)
                        .hasChildRecords(
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith().contract(HOOK_CONTRACT_NUM)),
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith().contract(HOOK_CONTRACT_NUM)),
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith().contract(HOOK_CONTRACT_NUM)),
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith().contract(HOOK_CONTRACT_NUM))),
                getAccountInfo(beneficiary)
                        .hasAlreadyUsedAutomaticAssociations(2)
                        .has(accountWith()
                                .newAssociationsFromSnapshot(
                                        beneficiary,
                                        List.of(
                                                relationshipWith(fungibleToken).balance(500),
                                                relationshipWith(uniqueToken).balance(1)))));
    }

    @HapiTest
    final Stream<DynamicTest> hookExecutionsWithHollowFinalization() {
        return hapiTest(flattened(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate("sponsor").balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoCreate(TOKEN_TREASURY)
                        .balance(0L)
                        .withHook(accountAllowanceHook(1L, TRUE_PRE_POST_ALLOWANCE_HOOK.name())),
                tokenCreate("token").treasury(TOKEN_TREASURY),
                cryptoCreate("test"),
                createHollowAccountFrom(SECP_256K1_SOURCE_KEY),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY)
                        .has(accountWith().maxAutoAssociations(-1).hasEmptyKey()),
                cryptoTransfer(moving(1, "token").between(TOKEN_TREASURY, SECP_256K1_SOURCE_KEY))
                        .payingWith(SECP_256K1_SOURCE_KEY)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                        .withPrePostHookFor(TOKEN_TREASURY, 1L, 25_000L, "")
                        .via("hollowTransfer"),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().hasNonEmptyKey()),
                getTxnRecord("hollowTransfer")
                        .andAllChildRecords()
                        .hasNonStakingChildRecordCount(3)
                        .hasChildRecords(
                                recordWith().status(SUCCESS).memo(LAZY_MEMO),
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith().contract(HOOK_CONTRACT_NUM)),
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith().contract(HOOK_CONTRACT_NUM)))));
    }

    @HapiTest
    final Stream<DynamicTest> hookExecutionsWithAliases() {
        final var args = TupleType.parse("(uint32)");
        return hapiTest(
                newKeyNamed("alias"),
                cryptoCreate("party").withHooks(accountAllowanceHook(1L, THREE_PASSES_HOOK.name())),
                cryptoCreate("counterparty"),
                cryptoTransfer(movingHbar(10L).between("party", "alias"))
                        .signedBy(DEFAULT_PAYER, "party")
                        .via("aliasCreation"),
                getTxnRecord("aliasCreation")
                        .hasChildRecords(recordWith().status(SUCCESS).memo(AUTO_MEMO)),
                cryptoTransfer((spec, b) -> b.setTransfers(TransferList.newBuilder()
                                .addAccountAmounts(AccountAmount.newBuilder()
                                        .setAccountID(spec.registry().getAccountID("party"))
                                        .setAmount(-123L)
                                        .setPreTxAllowanceHook(HookCall.newBuilder()
                                                .setHookId(1L)
                                                .setEvmHookCall(EvmHookCall.newBuilder()
                                                        .setGasLimit(42_000L)
                                                        .setData(ByteString.copyFrom(args.encode(Single.of(1L)))))))
                                .addAccountAmounts(AccountAmount.newBuilder()
                                        .setAccountID(spec.registry().getKeyAlias("alias"))
                                        .setAmount(+123L))))
                        .signedBy(DEFAULT_PAYER),
                withOpContext((spec, opLog) -> updateSpecFor(spec, "alias")),
                // Update aliased account with hook and use alias with hook invocation
                cryptoUpdateAliased("alias")
                        .withHook(accountAllowanceHook(1L, THREE_PASSES_HOOK.name()))
                        .signedBy(DEFAULT_PAYER, "alias"),
                cryptoTransfer((spec, b) -> b.setTransfers(TransferList.newBuilder()
                                .addAccountAmounts(AccountAmount.newBuilder()
                                        .setAccountID(spec.registry().getKeyAlias("alias"))
                                        .setAmount(-123L)
                                        .setPreTxAllowanceHook(HookCall.newBuilder()
                                                .setHookId(1L)
                                                .setEvmHookCall(EvmHookCall.newBuilder()
                                                        .setGasLimit(42_000L)
                                                        .setData(ByteString.copyFrom(args.encode(Single.of(1L)))))))
                                .addAccountAmounts(AccountAmount.newBuilder()
                                        .setAccountID(spec.registry().getAccountID("party"))
                                        .setAmount(+123L))))
                        .signedBy(DEFAULT_PAYER)
                        .via("aliasTransfer"),
                getTxnRecord("aliasTransfer").andAllChildRecords().logged());
    }

    /**
     * Returns the stream mode of the network under test, defaulting to {@link StreamMode#BOTH} if it is not yet
     * targeted.
     */
    private static StreamMode targetStreamMode() {
        final var network = SHARED_NETWORK.get();
        return network == null ? BOTH : network.startupProperties().getStreamMode("blockStream.streamMode");
    }

    /**
     * Returns an assertion that the unit of the given transfer has the given number of hook executions, each tracing
     * the writes to its hook storage (and any other contract's storage) with an explicit list of written keys.
     */
    private static BlockStreamAssertion hookExecutionsTraceExplicitWrites(
            @NonNull final HapiSpec spec, @NonNull final String transfer, final int numExecutions) {
        final var transferId = protoToPbj(spec.registry().getTxnId(transfer), TransactionID.class);
        return block -> {
            for (final var rawUnit : new RoleFreeBlockUnitSplit().split(block)) {
                final var parts = rawUnit.withBatchTransactionParts().blockTransactionParts();
                if (parts.stream().noneMatch(part -> part.transactionIdOrThrow().equals(transferId))) {
                    continue;
                }
                final var executions =
                        parts.stream().filter(BlockTransactionParts::isHookCall).toList();
                assertEquals(numExecutions, executions.size());
                for (final var execution : executions) {
                    final var usages = execution.tracesOrThrow().stream()
                            .filter(TraceData::hasEvmTraceData)
                            .flatMap(trace -> trace.evmTraceDataOrThrow().contractSlotUsages().stream())
                            .toList();
                    assertTrue(
                            usages.stream()
                                    .anyMatch(usage ->
                                            usage.contractIdOrThrow().contractNumOrThrow() == HTS_HOOKS_CONTRACT_NUM),
                            "Each hook execution uses its own storage");
                    assertTrue(
                            usages.stream().allMatch(ContractSlotUsage::hasWrittenSlotKeys),
                            "Each hook execution must trace its writes explicitly");
                }
                return true;
            }
            return false;
        };
    }
}

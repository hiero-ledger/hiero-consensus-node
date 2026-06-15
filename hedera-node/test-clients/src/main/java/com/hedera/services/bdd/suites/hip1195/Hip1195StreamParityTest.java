// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.AutoAssocAsserts.accountTokenPairs;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomAccount.INITIAL_BALANCE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.accountAmount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.tokenTransferList;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.transferList;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.wrapIntoTupleArray;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
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
import static com.hedera.node.app.hapi.utils.CommonUtils.asEvmAddress;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Single;
import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.EvmHookCall;
import com.hederahashgraph.api.proto.java.HookCall;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
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

    @Contract(contract = "TransferTokenHook", creationGas = 5_000_000L)
    static SpecContract TRANSFER_TOKEN_HOOK;

    @Contract(contract = "TransferTokenMultipleHook", creationGas = 5_000_000L)
    static SpecContract TRANSFER_TOKEN_MULTIPLE_HOOK;

    @Contract(contract = "MultipleContractCallsHook", creationGas = 1_000_000L)
    static SpecContract MULTIPLE_CONTRACT_CALLS_HOOK;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
        testLifecycle.doAdhoc(MULTIPURPOSE.getInfo());
        testLifecycle.doAdhoc(CREATE_OP_HOOK.getInfo());
        testLifecycle.doAdhoc(SET_AND_PASS_HOOK.getInfo());
        testLifecycle.doAdhoc(THREE_PASSES_HOOK.getInfo());
        testLifecycle.doAdhoc(TRUE_PRE_POST_ALLOWANCE_HOOK.getInfo());
        testLifecycle.doAdhoc(TRANSFER_TOKEN_HOOK.getInfo());
        testLifecycle.doAdhoc(TRANSFER_TOKEN_MULTIPLE_HOOK.getInfo());
        testLifecycle.doAdhoc(MULTIPLE_CONTRACT_CALLS_HOOK.getInfo());
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
        // Calldata layout for TransferTokenHook: (address token, address receiver, int64 amount)
        // The hook calls transferToken(token, context.owner, receiver, amount) on the HTS precompile
        final var HOOK_CALLDATA_TYPE = TupleType.parse("(address,address,int64)");
        final var FUNGIBLE_TOKEN = "fungibleToken";
        final long HOOK_GAS_LIMIT = 500_000L;
        final long HOOK_TOKEN_AMOUNT = 5L;
        final long TOTAL_SUPPLY = 1_000L;

        return hapiTest(
                newKeyNamed("alias"),
                // Create party with balance
                cryptoCreate("party")
                        .balance(10 * ONE_HUNDRED_HBARS)
                        .maxAutomaticTokenAssociations(5),
                // Create counterparty who will receive tokens from the hook's inner transferToken
                cryptoCreate("counterparty").maxAutomaticTokenAssociations(5),
                // Create a fungible token for the hook to transfer
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(TOTAL_SUPPLY)
                        .treasury(TOKEN_TREASURY),
                // Auto-create aliased account via HBAR transfer
                cryptoTransfer(movingHbar(ONE_HUNDRED_HBARS).between("party", "alias"))
                        .signedBy(DEFAULT_PAYER, "party")
                        .via("aliasCreation"),
                getTxnRecord("aliasCreation")
                        .hasChildRecords(recordWith().status(SUCCESS).memo(AUTO_MEMO)),
                withOpContext((spec, opLog) -> updateSpecFor(spec, "alias")),
                // Associate alias with the token and give it some tokens
                tokenAssociate("alias", List.of(FUNGIBLE_TOKEN)),
                tokenAssociate("counterparty", List.of(FUNGIBLE_TOKEN)),
                cryptoTransfer(moving(100, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, "alias")),
                // Attach TransferTokenHook to the aliased account; the hook calls
                // HTS.transferToken(token, owner, receiver, amount) on the HTS precompile
                cryptoUpdateAliased("alias")
                        .withHook(accountAllowanceHook(1L, TRANSFER_TOKEN_HOOK.name()))
                        .signedBy(DEFAULT_PAYER, "alias"),
                // Trigger the hook: the main transfer sends 10 tinybar from alias to party,
                // and the hook performs transferToken of HOOK_TOKEN_AMOUNT fungible tokens
                // from alias (the hook owner) to counterparty via the HTS precompile
                withOpContext((spec, opLog) -> {
                    final var aliasId = spec.registry().getAccountID("alias");
                    final var partyId = spec.registry().getAccountID("party");

                    // Build the EVM addresses for the hook calldata
                    final long tokenNum = spec.registry().getTokenID(FUNGIBLE_TOKEN).getTokenNum();
                    final var tokenAddr = Address.wrap(Address.toChecksumAddress(
                            new java.math.BigInteger(1, asEvmAddress(tokenNum))));
                    final long counterpartyNum =
                            spec.registry().getAccountID("counterparty").getAccountNum();
                    final var counterpartyAddr = Address.wrap(Address.toChecksumAddress(
                            new java.math.BigInteger(1, asEvmAddress(counterpartyNum))));

                    allRunFor(
                            spec,
                            cryptoTransfer((s, b) -> b.setTransfers(TransferList.newBuilder()
                                            .addAccountAmounts(AccountAmount.newBuilder()
                                                    .setAccountID(aliasId)
                                                    .setAmount(-10L)
                                                    .setPreTxAllowanceHook(HookCall.newBuilder()
                                                            .setHookId(1L)
                                                            .setEvmHookCall(EvmHookCall.newBuilder()
                                                                    .setGasLimit(HOOK_GAS_LIMIT)
                                                                    .setData(ByteString.copyFrom(
                                                                            HOOK_CALLDATA_TYPE
                                                                                    .encode(Tuple.of(
                                                                                            tokenAddr,
                                                                                            counterpartyAddr,
                                                                                            HOOK_TOKEN_AMOUNT)))))))
                                            .addAccountAmounts(AccountAmount.newBuilder()
                                                    .setAccountID(partyId)
                                                    .setAmount(+10L))))
                                    .signedBy(DEFAULT_PAYER)
                                    .via("hookTransfer"));
                }),
                // Log the full transaction record including all child records from hook execution.
                // Child 1: the hook execution (ContractCall to 0.0.365)
                // Child 2: the inner transferToken dispatched by the hook via the HTS precompile
                getTxnRecord("hookTransfer")
                        .andAllChildRecords()
                        .hasChildRecords(
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith().contract(HOOK_CONTRACT_NUM)),
                                recordWith().status(SUCCESS))
                        .logged(),
                // Verify that the counterparty received the tokens from the hook's transferToken call
                getAccountInfo("counterparty")
                        .hasToken(relationshipWith(FUNGIBLE_TOKEN).balance(HOOK_TOKEN_AMOUNT)));
    }

    @HapiTest
    final Stream<DynamicTest> hookExecutionsWithAliasesMultiple() {
        // Calldata layout for TransferTokenMultipleHook:
        //   (address token, address receiver1, address receiver2, address receiver3, int64 transferAmount)
        // The hook calls transferToken 3 times (owner->receiver1, owner->receiver2, owner->receiver3)
        final var HOOK_CALLDATA_TYPE = TupleType.parse("(address,address,address,address,int64)");
        final var FUNGIBLE_TOKEN = "fungibleToken";
        final var FUNGIBLE_TOKEN_2 = "fungibleToken2";
        final long HOOK_GAS_LIMIT = 3_000_000L;
        final long HOOK_TOKEN_AMOUNT = 5L;
        final long TOTAL_SUPPLY = 1_000L;

        return hapiTest(
                newKeyNamed("alias"),
                // Create party with balance
                cryptoCreate("party")
                        .balance(10 * ONE_HUNDRED_HBARS)
                        .maxAutomaticTokenAssociations(5),
                // Create three counterparties who will receive tokens from the hook's inner transfers
                cryptoCreate("counterparty1").maxAutomaticTokenAssociations(5),
                cryptoCreate("counterparty2").maxAutomaticTokenAssociations(5),
                cryptoCreate("counterparty3").maxAutomaticTokenAssociations(5),
                // Create fungible tokens
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(TOTAL_SUPPLY)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN_2)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(TOTAL_SUPPLY)
                        .treasury(TOKEN_TREASURY),
                // Auto-create aliased account via HBAR transfer
                cryptoTransfer(movingHbar(ONE_HUNDRED_HBARS).between("party", "alias"))
                        .signedBy(DEFAULT_PAYER, "party")
                        .via("aliasCreation"),
                getTxnRecord("aliasCreation")
                        .hasChildRecords(recordWith().status(SUCCESS).memo(AUTO_MEMO)),
                withOpContext((spec, opLog) -> updateSpecFor(spec, "alias")),
                // Associate alias and counterparties with the token, then fund the alias
                tokenAssociate("alias", List.of(FUNGIBLE_TOKEN, FUNGIBLE_TOKEN_2)),
                tokenAssociate("counterparty1", List.of(FUNGIBLE_TOKEN)),
                tokenAssociate("counterparty2", List.of(FUNGIBLE_TOKEN)),
                tokenAssociate("counterparty3", List.of(FUNGIBLE_TOKEN)),
                cryptoTransfer(
                        moving(100, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, "alias"),
                        moving(100, FUNGIBLE_TOKEN_2).between(TOKEN_TREASURY, "alias")),
                // Attach TransferTokenMultipleHook to the aliased account
                cryptoUpdateAliased("alias")
                        .withHooks(
                                accountAllowanceHook(1L, TRANSFER_TOKEN_MULTIPLE_HOOK.name()),
                                accountAllowanceHook(2L, TRANSFER_TOKEN_MULTIPLE_HOOK.name()),
                                accountAllowanceHook(3L, TRANSFER_TOKEN_MULTIPLE_HOOK.name()))
                        .signedBy(DEFAULT_PAYER, "alias"),
                // Trigger three hooks: the main HBAR transfer triggers hook 1,
                // a FUNGIBLE_TOKEN transfer triggers hook 2, and a FUNGIBLE_TOKEN_2
                // transfer triggers hook 3. Each hook performs 3 separate
                // transferToken calls (each HOOK_TOKEN_AMOUNT) via the HTS precompile.
                withOpContext((spec, opLog) -> {
                    final var aliasId = spec.registry().getAccountID("alias");
                    final var partyId = spec.registry().getAccountID("party");

                    // Build the EVM addresses for the hook calldata
                    final long tokenNum =
                            spec.registry().getTokenID(FUNGIBLE_TOKEN).getTokenNum();
                    final var tokenAddr = Address.wrap(Address.toChecksumAddress(
                            new java.math.BigInteger(1, asEvmAddress(tokenNum))));
                    final long cp1Num =
                            spec.registry().getAccountID("counterparty1").getAccountNum();
                    final var cp1Addr = Address.wrap(
                            Address.toChecksumAddress(new java.math.BigInteger(1, asEvmAddress(cp1Num))));
                    final long cp2Num =
                            spec.registry().getAccountID("counterparty2").getAccountNum();
                    final var cp2Addr = Address.wrap(
                            Address.toChecksumAddress(new java.math.BigInteger(1, asEvmAddress(cp2Num))));
                    final long cp3Num =
                            spec.registry().getAccountID("counterparty3").getAccountNum();
                    final var cp3Addr = Address.wrap(
                            Address.toChecksumAddress(new java.math.BigInteger(1, asEvmAddress(cp3Num))));

                    // Shared hook calldata for all 3 hooks:
                    //   (token, receiver1, receiver2, receiver3, transferAmount)
                    final var hookCalldata = ByteString.copyFrom(
                            HOOK_CALLDATA_TYPE.encode(Tuple.of(
                                    tokenAddr, cp1Addr, cp2Addr, cp3Addr, HOOK_TOKEN_AMOUNT)));

                    allRunFor(
                            spec,
                            cryptoTransfer((s, b) -> {
                                        // HBAR transfer with hook 1
                                        b.setTransfers(TransferList.newBuilder()
                                                .addAccountAmounts(AccountAmount.newBuilder()
                                                        .setAccountID(aliasId)
                                                        .setAmount(-10L)
                                                        .setPreTxAllowanceHook(HookCall.newBuilder()
                                                                .setHookId(1L)
                                                                .setEvmHookCall(EvmHookCall.newBuilder()
                                                                        .setGasLimit(HOOK_GAS_LIMIT)
                                                                        .setData(hookCalldata))))
                                                .addAccountAmounts(AccountAmount.newBuilder()
                                                        .setAccountID(partyId)
                                                        .setAmount(+10L)));
                                        // Token transfer (FUNGIBLE_TOKEN) with hook 2
                                        b.addTokenTransfers(TokenTransferList.newBuilder()
                                                .setToken(s.registry().getTokenID(FUNGIBLE_TOKEN))
                                                .addTransfers(AccountAmount.newBuilder()
                                                        .setAccountID(aliasId)
                                                        .setAmount(-1L)
                                                        .setPreTxAllowanceHook(HookCall.newBuilder()
                                                                .setHookId(2L)
                                                                .setEvmHookCall(EvmHookCall.newBuilder()
                                                                        .setGasLimit(HOOK_GAS_LIMIT)
                                                                        .setData(hookCalldata))))
                                                .addTransfers(AccountAmount.newBuilder()
                                                        .setAccountID(partyId)
                                                        .setAmount(+1L)));
                                        // Token transfer (FUNGIBLE_TOKEN_2) with hook 3
                                        b.addTokenTransfers(TokenTransferList.newBuilder()
                                                .setToken(s.registry().getTokenID(FUNGIBLE_TOKEN_2))
                                                .addTransfers(AccountAmount.newBuilder()
                                                        .setAccountID(aliasId)
                                                        .setAmount(-1L)
                                                        .setPreTxAllowanceHook(HookCall.newBuilder()
                                                                .setHookId(3L)
                                                                .setEvmHookCall(EvmHookCall.newBuilder()
                                                                        .setGasLimit(HOOK_GAS_LIMIT)
                                                                        .setData(hookCalldata))))
                                                .addTransfers(AccountAmount.newBuilder()
                                                        .setAccountID(partyId)
                                                        .setAmount(+1L)));
                                    })
                                    .signedBy(DEFAULT_PAYER)
                                    .via("hookTransfer"));
                }),
                // Log the full transaction record including all child records from hook execution.
                // Each of the 3 hooks produces 1 ContractCall child + 3 inner transferToken children = 4 each.
                // Total: 12 child records.
                getTxnRecord("hookTransfer")
                        .andAllChildRecords()
                        .hasChildRecords(
                                // Hook 1 (HBAR transfer trigger): execution + 3 inner transfers
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith().contract(HOOK_CONTRACT_NUM)),
                                recordWith().status(SUCCESS),
                                recordWith().status(SUCCESS),
                                recordWith().status(SUCCESS),
                                // Hook 2 (FUNGIBLE_TOKEN transfer trigger): execution + 3 inner transfers
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith().contract(HOOK_CONTRACT_NUM)),
                                recordWith().status(SUCCESS),
                                recordWith().status(SUCCESS),
                                recordWith().status(SUCCESS),
                                // Hook 3 (FUNGIBLE_TOKEN_2 transfer trigger): execution + 3 inner transfers
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith().contract(HOOK_CONTRACT_NUM)),
                                recordWith().status(SUCCESS),
                                recordWith().status(SUCCESS),
                                recordWith().status(SUCCESS))
                        .logged(),
                // Verify that each counterparty received tokens from all 3 hooks' transferToken calls
                // (5 tokens per hook × 3 hooks = 15 tokens each)
                getAccountInfo("counterparty1")
                        .hasToken(relationshipWith(FUNGIBLE_TOKEN).balance(3 * HOOK_TOKEN_AMOUNT)),
                getAccountInfo("counterparty2")
                        .hasToken(relationshipWith(FUNGIBLE_TOKEN).balance(3 * HOOK_TOKEN_AMOUNT)),
                getAccountInfo("counterparty3")
                        .hasToken(relationshipWith(FUNGIBLE_TOKEN).balance(3 * HOOK_TOKEN_AMOUNT)));
    }

    @HapiTest
    final Stream<DynamicTest> hookMultipleContractCalls() {
        // Hook triggered on CryptoTransfer; allow() makes 2 nested contract calls to
        // Multipurpose (believeIn(1), believeIn(2)) - 3 contract calls total, no precompiles.
        // Hooks 1, 2, 3 (same contract) are triggered by HBAR transfer, token1 transfer, token2 transfer.
        final var HOOK_CALLDATA_TYPE = TupleType.parse("(address)");
        final var TOKEN_1 = "token1";
        final var TOKEN_2 = "token2";
        final long HOOK_GAS_LIMIT = 500_000L;
        final long TOTAL_SUPPLY = 1_000L;

        return hapiTest(
                newKeyNamed("alias"),
                cryptoCreate("party").balance(10 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(2),
                cryptoCreate("counterparty"),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(TOKEN_1)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(TOTAL_SUPPLY)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(TOKEN_2)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(TOTAL_SUPPLY)
                        .treasury(TOKEN_TREASURY),
                // Auto-create aliased account via HBAR transfer
                cryptoTransfer(movingHbar(ONE_HUNDRED_HBARS).between("party", "alias"))
                        .signedBy(DEFAULT_PAYER, "party")
                        .via("aliasCreation"),
                getTxnRecord("aliasCreation")
                        .hasChildRecords(recordWith().status(SUCCESS).memo(AUTO_MEMO)),
                withOpContext((spec, opLog) -> updateSpecFor(spec, "alias")),
                tokenAssociate("alias", List.of(TOKEN_1, TOKEN_2)),
                tokenAssociate("party", List.of(TOKEN_1, TOKEN_2)),
                cryptoTransfer(
                        moving(100, TOKEN_1).between(TOKEN_TREASURY, "alias"),
                        moving(100, TOKEN_2).between(TOKEN_TREASURY, "alias")),
                // Attach MultipleContractCallsHook as hooks 1, 2 and 3 on the aliased account
                cryptoUpdateAliased("alias")
                        .withHooks(
                                accountAllowanceHook(1L, MULTIPLE_CONTRACT_CALLS_HOOK.name()),
                                accountAllowanceHook(2L, MULTIPLE_CONTRACT_CALLS_HOOK.name()),
                                accountAllowanceHook(3L, MULTIPLE_CONTRACT_CALLS_HOOK.name()))
                        .signedBy(DEFAULT_PAYER, "alias"),
                // Trigger hook 1 via HBAR, hook 2 via token1, hook 3 via token2 (no repeated account in same list)
                withOpContext((spec, opLog) -> {
                    final var aliasId = spec.registry().getAccountID("alias");
                    final var partyId = spec.registry().getAccountID("party");
                    final var hookData = ByteString.copyFrom(HOOK_CALLDATA_TYPE.encode(
                            Single.of(MULTIPURPOSE.addressOn(spec.targetNetworkOrThrow()))));
                    final var evmHookCall = EvmHookCall.newBuilder()
                            .setGasLimit(HOOK_GAS_LIMIT)
                            .setData(hookData)
                            .build();
                    allRunFor(
                            spec,
                            cryptoTransfer((s, b) -> {
                                // HBAR transfer with hook 1
                                b.setTransfers(TransferList.newBuilder()
                                        .addAccountAmounts(AccountAmount.newBuilder()
                                                .setAccountID(aliasId)
                                                .setAmount(-10L)
                                                .setPreTxAllowanceHook(HookCall.newBuilder()
                                                        .setHookId(1L)
                                                        .setEvmHookCall(evmHookCall)))
                                        .addAccountAmounts(AccountAmount.newBuilder()
                                                .setAccountID(partyId)
                                                .setAmount(+10L)));
                                // Token transfer (TOKEN_1) with hook 2
                                b.addTokenTransfers(TokenTransferList.newBuilder()
                                        .setToken(s.registry().getTokenID(TOKEN_1))
                                        .addTransfers(AccountAmount.newBuilder()
                                                .setAccountID(aliasId)
                                                .setAmount(-1L)
                                                .setPreTxAllowanceHook(HookCall.newBuilder()
                                                        .setHookId(2L)
                                                        .setEvmHookCall(evmHookCall)))
                                        .addTransfers(AccountAmount.newBuilder()
                                                .setAccountID(partyId)
                                                .setAmount(+1L)));
                                // Token transfer (TOKEN_2) with hook 3
                                b.addTokenTransfers(TokenTransferList.newBuilder()
                                        .setToken(s.registry().getTokenID(TOKEN_2))
                                        .addTransfers(AccountAmount.newBuilder()
                                                .setAccountID(aliasId)
                                                .setAmount(-1L)
                                                .setPreTxAllowanceHook(HookCall.newBuilder()
                                                        .setHookId(3L)
                                                        .setEvmHookCall(evmHookCall)))
                                        .addTransfers(AccountAmount.newBuilder()
                                                .setAccountID(partyId)
                                                .setAmount(+1L)));
                            })
                                    .signedBy(DEFAULT_PAYER)
                                    .via("hookTransfer"));
                }),
                // 3 child records: one per hook execution (hook 1, 2, 3)
                getTxnRecord("hookTransfer")
                        .andAllChildRecords()
                        .hasNonStakingChildRecordCount(3)
                        .hasChildRecords(
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith().contract(HOOK_CONTRACT_NUM)),
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith().contract(HOOK_CONTRACT_NUM)),
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith().contract(HOOK_CONTRACT_NUM)))
                        .logged());
    }
}

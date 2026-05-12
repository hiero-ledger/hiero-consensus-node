// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.AutoAssocAsserts.accountTokenPairs;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomAccount.INITIAL_BALANCE;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdateAliased;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
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
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Single;
import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
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

// Hooks are enabled by default so it is safe to run this concurrently
@HapiTestLifecycle
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

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
        testLifecycle.doAdhoc(MULTIPURPOSE.getInfo());
        testLifecycle.doAdhoc(CREATE_OP_HOOK.getInfo());
        testLifecycle.doAdhoc(SET_AND_PASS_HOOK.getInfo());
        testLifecycle.doAdhoc(THREE_PASSES_HOOK.getInfo());
        testLifecycle.doAdhoc(TRUE_PRE_POST_ALLOWANCE_HOOK.getInfo());
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
}

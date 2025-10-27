// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195;

import static com.hedera.services.bdd.junit.TestTags.ADHOC;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.LAZY_MEMO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenSupplyType.FINITE;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;

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
import java.util.Map;
import java.util.function.LongFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@HapiTestLifecycle
@SuppressWarnings({"rawtypes", "unchecked"})
@Tag(ADHOC)
public class Hip1195StreamParityTest {
    private static final TupleType SET_AND_PASS_ARGS = TupleType.parse("(uint32,address)");

    @Contract(contract = "Multipurpose", creationGas = 500_000L)
    static SpecContract MULTIPURPOSE;

    @Contract(contract = "SetAndPassHook", creationGas = 1_000_000L)
    static SpecContract SET_AND_PASS_HOOK;

    @Contract(contract = "ThreePassesHook", creationGas = 1_000_000L)
    static SpecContract THREE_PASSES_HOOK;

    @Contract(contract = "TruePrePostHook", creationGas = 5_000_000)
    static SpecContract TRUE_PRE_POST_ALLOWANCE_HOOK;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
        testLifecycle.doAdhoc(MULTIPURPOSE.getInfo());
        testLifecycle.doAdhoc(SET_AND_PASS_HOOK.getInfo());
        testLifecycle.doAdhoc(THREE_PASSES_HOOK.getInfo());
        testLifecycle.doAdhoc(TRUE_PRE_POST_ALLOWANCE_HOOK.getInfo());
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
                                                    .setData(ByteString.copyFrom(
                                                            SET_AND_PASS_ARGS.encode(Tuple.of(1L, targetAddress)))))))
                            .addAccountAmounts(AccountAmount.newBuilder()
                                    .setAccountID(counterpartyId)
                                    .setAmount(+123L)
                                    .setPrePostTxAllowanceHook(HookCall.newBuilder()
                                            .setHookId(3L)
                                            .setEvmHookCall(EvmHookCall.newBuilder()
                                                    .setGasLimit(gasLimit)
                                                    .setData(ByteString.copyFrom(
                                                            SET_AND_PASS_ARGS.encode(Tuple.of(3L, targetAddress))))))));
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
                                                    .setData(ByteString.copyFrom(
                                                            SET_AND_PASS_ARGS.encode(Tuple.of(2L, targetAddress)))))))
                            .addTransfers(AccountAmount.newBuilder()
                                    .setAccountID(counterpartyId)
                                    .setAmount(+1L)
                                    .setPreTxAllowanceHook(HookCall.newBuilder()
                                            .setHookId(4L)
                                            .setEvmHookCall(EvmHookCall.newBuilder()
                                                    .setGasLimit(gasLimit)
                                                    .setData(ByteString.copyFrom(
                                                            SET_AND_PASS_ARGS.encode(Tuple.of(4L, targetAddress))))))));
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
                                                    .setData(ByteString.copyFrom(
                                                            SET_AND_PASS_ARGS.encode(Tuple.of(12L, targetAddress))))))
                                    .setReceiverAccountID(partyId)
                                    .setPreTxReceiverAllowanceHook(HookCall.newBuilder()
                                            .setHookId(2L)
                                            .setEvmHookCall(EvmHookCall.newBuilder()
                                                    .setGasLimit(gasLimit)
                                                    .setData(ByteString.copyFrom(SET_AND_PASS_ARGS.encode(
                                                            Tuple.of(14L, targetAddress))))))));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> hookExecutionsWithAutoCreations(){
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
                        .hasNonStakingChildRecordCount(5) // one auto-creation, four hook invocations pre
                        // and post for hbar and token transfers
                        .hasChildRecords(recordWith().status(SUCCESS).memo(LAZY_MEMO),
                                recordWith().status(SUCCESS).memo(LAZY_MEMO))
                        .logged(),
                getAliasedAccountInfo("alias")
                        .has(accountWith().balance(10L))
                        .hasToken(relationshipWith("tokenA")));
    }
}

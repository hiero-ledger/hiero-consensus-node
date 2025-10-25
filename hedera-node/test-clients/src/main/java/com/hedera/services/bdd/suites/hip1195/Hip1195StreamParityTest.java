// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195;

import static com.hedera.services.bdd.junit.TestTags.ADHOC;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;

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
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.EvmHookCall;
import com.hederahashgraph.api.proto.java.HookCall;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@HapiTestLifecycle
@SuppressWarnings({"rawtypes", "unchecked"})
public class Hip1195StreamParityTest {
    private static final TupleType SET_AND_PASS_ARGS = TupleType.parse("(uint32,address)");

    @Contract(contract = "Multipurpose", creationGas = 500_000L)
    static SpecContract MULTIPURPOSE;

    @Contract(contract = "SetAndPassHook", creationGas = 1_000_000L)
    static SpecContract SET_AND_PASS_HOOK;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
        testLifecycle.doAdhoc(MULTIPURPOSE.getInfo());
        testLifecycle.doAdhoc(SET_AND_PASS_HOOK.getInfo());
    }

    @HapiTest
    @Tag(ADHOC)
    final Stream<DynamicTest> isolatedExecutionWithNonHookStorageSideEffectsPassesParity() {
        return hapiTest(
                cryptoCreate("party")
                        .withHooks(
                                accountAllowanceHook(1L, SET_AND_PASS_HOOK.name())),
                cryptoCreate("counterparty"),
                cryptoTransfer((spec, b) -> b.setTransfers(TransferList.newBuilder()
                        .addAccountAmounts(AccountAmount.newBuilder()
                                .setAccountID(spec.registry().getAccountID("party"))
                                .setAmount(-123L)
                                .setPrePostTxAllowanceHook(HookCall.newBuilder()
                                        .setHookId(1L)
                                        .setEvmHookCall(EvmHookCall.newBuilder()
                                                .setGasLimit(42_000L)
                                                .setData(ByteString.copyFrom(
                                                        SET_AND_PASS_ARGS.encode(Tuple.of(666L, MULTIPURPOSE.addressOn(spec.targetNetworkOrThrow()))))))))
                        .addAccountAmounts(AccountAmount.newBuilder()
                                .setAccountID(spec.registry().getAccountID("counterparty"))
                                .setAmount(+123L)))).signedBy(DEFAULT_PAYER)
                );
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
                                                    .setData(ByteString.copyFrom(
                                                            SET_AND_PASS_ARGS.encode(Tuple.of(14L, targetAddress))))))));
                }));
    }
}

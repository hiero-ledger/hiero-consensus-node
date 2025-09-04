// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.lambdaAccountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.accountLambdaSStore;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.contract.Utils.aaWith;
import static com.hedera.services.bdd.suites.contract.Utils.aaWithPreHook;
import static com.hedera.services.bdd.suites.contract.Utils.aaWithPrePostHook;
import static com.hedera.services.bdd.suites.contract.Utils.ocWith;
import static com.hedera.services.bdd.suites.crypto.CryptoCreateSuite.CIVILIAN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOKS_NOT_ENABLED;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.EvmHookCall;
import com.hedera.hapi.node.base.HookCall;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TokenType;
import com.hederahashgraph.api.proto.java.TransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@HapiTestLifecycle
public class Hip1195DisabledTest {
    @Contract(contract = "PayableConstructor")
    static SpecContract HOOK_CONTRACT;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hedera.hooksEnabled", "false"));
        testLifecycle.doAdhoc(HOOK_CONTRACT.getInfo());
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> cannotUseLambdaSStoreWhenHooksDisabled() {
        return hapiTest(accountLambdaSStore(DEFAULT_PAYER, 123L)
                .putSlot(Bytes.EMPTY, Bytes.EMPTY)
                .hasPrecheck(HOOKS_NOT_ENABLED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotCreateAccountsOrContractsWithHooksWhenDisabled() {
        return hapiTest(
                cryptoCreate("notToBe")
                        .withHook(lambdaAccountAllowanceHook(123L, HOOK_CONTRACT.name()))
                        .hasPrecheck(HOOKS_NOT_ENABLED),
                contractCreate("notToBe")
                        .inlineInitCode(ByteString.EMPTY)
                        .withHook(lambdaAccountAllowanceHook(123L, HOOK_CONTRACT.name()))
                        .hasPrecheck(HOOKS_NOT_ENABLED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUpdateAccountsOrContractsWithHooksWhenDisabled() {
        return hapiTest(
                cryptoCreate(CIVILIAN),
                cryptoUpdate(CIVILIAN)
                        .withHook(lambdaAccountAllowanceHook(123L, HOOK_CONTRACT.name()))
                        .hasPrecheck(HOOKS_NOT_ENABLED),
                cryptoUpdate(CIVILIAN).removingHook(123L).hasPrecheck(HOOKS_NOT_ENABLED),
                contractUpdate(HOOK_CONTRACT.name())
                        .withHook(lambdaAccountAllowanceHook(123L, HOOK_CONTRACT.name()))
                        .hasPrecheck(HOOKS_NOT_ENABLED),
                contractUpdate(HOOK_CONTRACT.name()).removingHook(123L).hasPrecheck(HOOKS_NOT_ENABLED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUseAnyTransferHookWhenDisabled() {
        final var hookCall = HookCall.newBuilder()
                .hookId(123L)
                .evmHookCall(
                        EvmHookCall.newBuilder().data(Bytes.fromHex("abcd")).gasLimit(33_333L))
                .build();
        final var protoHookCall = fromPbj(hookCall);
        return hapiTest(
                cryptoCreate(CIVILIAN).maxAutomaticTokenAssociations(5),
                tokenCreate("ft").initialSupply(123).treasury(DEFAULT_PAYER),
                tokenCreate("nft")
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(DEFAULT_PAYER)
                        .treasury(DEFAULT_PAYER),
                mintToken("nft", List.of(ByteString.fromHex("abcd"))),
                // Every transfer operation that uses a hook should fail with HOOKS_NOT_ENABLED
                cryptoTransfer((spec, b) -> {
                            final var registry = spec.registry();
                            b.setTransfers(TransferList.newBuilder()
                                    .addAccountAmounts(
                                            aaWithPreHook(registry.getAccountID(DEFAULT_PAYER), -1, hookCall))
                                    .addAccountAmounts(aaWith(registry.getAccountID(CIVILIAN), +1))
                                    .build());
                        })
                        .signedBy(DEFAULT_PAYER)
                        .hasPrecheck(HOOKS_NOT_ENABLED),
                cryptoTransfer((spec, b) -> {
                            final var registry = spec.registry();
                            b.addTokenTransfers(TokenTransferList.newBuilder()
                                    .setToken(registry.getTokenID("ft"))
                                    .addTransfers(aaWithPreHook(registry.getAccountID(DEFAULT_PAYER), -1, hookCall))
                                    .addTransfers(aaWith(registry.getAccountID(CIVILIAN), +1))
                                    .build());
                        })
                        .signedBy(DEFAULT_PAYER)
                        .hasPrecheck(HOOKS_NOT_ENABLED),
                cryptoTransfer((spec, b) -> {
                            final var registry = spec.registry();
                            b.addTokenTransfers(TokenTransferList.newBuilder()
                                    .setToken(registry.getTokenID("ft"))
                                    .addTransfers(aaWithPrePostHook(registry.getAccountID(DEFAULT_PAYER), -1, hookCall))
                                    .addTransfers(aaWith(registry.getAccountID(CIVILIAN), +1))
                                    .build());
                        })
                        .signedBy(DEFAULT_PAYER)
                        .hasPrecheck(HOOKS_NOT_ENABLED),
                cryptoTransfer((spec, b) -> {
                            final var registry = spec.registry();
                            b.addTokenTransfers(TokenTransferList.newBuilder()
                                    .setToken(registry.getTokenID("ft"))
                                    .addNftTransfers(ocWith(
                                            registry.getAccountID(DEFAULT_PAYER),
                                            registry.getAccountID(CIVILIAN),
                                            1L,
                                            oc -> oc.setPreTxSenderAllowanceHook(protoHookCall)))
                                    .build());
                        })
                        .signedBy(DEFAULT_PAYER)
                        .hasPrecheck(HOOKS_NOT_ENABLED),
                cryptoTransfer((spec, b) -> {
                            final var registry = spec.registry();
                            b.addTokenTransfers(TokenTransferList.newBuilder()
                                    .setToken(registry.getTokenID("ft"))
                                    .addNftTransfers(ocWith(
                                            registry.getAccountID(DEFAULT_PAYER),
                                            registry.getAccountID(CIVILIAN),
                                            1L,
                                            oc -> oc.setPrePostTxSenderAllowanceHook(protoHookCall)))
                                    .build());
                        })
                        .signedBy(DEFAULT_PAYER)
                        .hasPrecheck(HOOKS_NOT_ENABLED),
                cryptoTransfer((spec, b) -> {
                            final var registry = spec.registry();
                            b.addTokenTransfers(TokenTransferList.newBuilder()
                                    .setToken(registry.getTokenID("ft"))
                                    .addNftTransfers(ocWith(
                                            registry.getAccountID(DEFAULT_PAYER),
                                            registry.getAccountID(CIVILIAN),
                                            1L,
                                            oc -> oc.setPreTxReceiverAllowanceHook(protoHookCall)))
                                    .build());
                        })
                        .signedBy(DEFAULT_PAYER)
                        .hasPrecheck(HOOKS_NOT_ENABLED),
                cryptoTransfer((spec, b) -> {
                            final var registry = spec.registry();
                            b.addTokenTransfers(TokenTransferList.newBuilder()
                                    .setToken(registry.getTokenID("ft"))
                                    .addNftTransfers(ocWith(
                                            registry.getAccountID(DEFAULT_PAYER),
                                            registry.getAccountID(CIVILIAN),
                                            1L,
                                            oc -> oc.setPrePostTxReceiverAllowanceHook(protoHookCall)))
                                    .build());
                        })
                        .signedBy(DEFAULT_PAYER)
                        .hasPrecheck(HOOKS_NOT_ENABLED));
    }
}

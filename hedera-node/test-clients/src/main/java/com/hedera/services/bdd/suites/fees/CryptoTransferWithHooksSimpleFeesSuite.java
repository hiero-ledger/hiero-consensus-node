// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@OrderedInIsolation
@HapiTestLifecycle
public class CryptoTransferWithHooksSimpleFeesSuite {
    private static final double HBAR_TRANSFER_FEE = 0.0001;
    private static final double TOKEN_TRANSFER_FEE = 0.001;
    private static final double HOOK_INVOCATION_USD = 0.005;
    private static final double NFT_TRANSFER_BASE_USD = 0.001;

    private static final String PAYER = "payer";
    private static final String RECEIVER = "receiver";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String NFT_TOKEN = "nftToken";
    private static final String HOOK_CONTRACT = "TruePreHook";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true", "hooks.hooksEnabled", "true"));
    }

    @HapiTest
    @DisplayName("HOOKS: HBAR transfer with single hook")
    final Stream<DynamicTest> hbarTransferWithHook() {
        return hapiTest(
                uploadInitCode(HOOK_CONTRACT),
                contractCreate(HOOK_CONTRACT).gas(5_000_000),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS).withHook(accountAllowanceHook(1L, HOOK_CONTRACT)),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, RECEIVER))
                        .withPreHookFor(PAYER, 1L, 5_000_000L, "")
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(50 * ONE_HBAR)
                        .via("hbarWithHookTxn"),
                sourcingContextual(spec -> {
                    final long tinybarGasCost =
                            5_000_000L * spec.ratesProvider().currentTinybarGasPrice();
                    final double usdGasCost = spec.ratesProvider().toUsdWithActiveRates(tinybarGasCost);
                    return validateChargedUsd("hbarWithHookTxn", HBAR_TRANSFER_FEE + HOOK_INVOCATION_USD + usdGasCost);
                }));
    }

    @HapiTest
    @DisplayName("HOOKS: Token transfer with single hook")
    final Stream<DynamicTest> tokenTransferWithHook() {
        return hapiTest(
                uploadInitCode(HOOK_CONTRACT),
                contractCreate(HOOK_CONTRACT).gas(5_000_000),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS).withHook(accountAllowanceHook(1L, HOOK_CONTRACT)),
                cryptoCreate(RECEIVER).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(100, FUNGIBLE_TOKEN).between(PAYER, RECEIVER))
                        .withPreHookFor(PAYER, 1L, 5_000_000L, "")
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(50 * ONE_HBAR)
                        .via("tokenWithHookTxn"),
                sourcingContextual(spec -> {
                    final long tinybarGasCost =
                            5_000_000L * spec.ratesProvider().currentTinybarGasPrice();
                    final double usdGasCost = spec.ratesProvider().toUsdWithActiveRates(tinybarGasCost);
                    return validateChargedUsd(
                            "tokenWithHookTxn", TOKEN_TRANSFER_FEE + HOOK_INVOCATION_USD + usdGasCost);
                }));
    }

    @HapiTest
    @DisplayName("HOOKS: NFT transfer with multiple hooks (sender + receiver)")
    final Stream<DynamicTest> nftTransferWithMultipleHooks() {
        return hapiTest(
                uploadInitCode(HOOK_CONTRACT),
                contractCreate(HOOK_CONTRACT).gas(5_000_000),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS).withHook(accountAllowanceHook(1L, HOOK_CONTRACT)),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).withHook(accountAllowanceHook(2L, HOOK_CONTRACT)),
                tokenCreate(NFT_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                mintToken(NFT_TOKEN, List.of(metadata(1))),
                tokenAssociate(RECEIVER, NFT_TOKEN),
                cryptoTransfer(movingUnique(NFT_TOKEN, 1L).between(PAYER, RECEIVER))
                        .withNftSenderPreHookFor(PAYER, 1L, 5_000_000L, "")
                        .withNftReceiverPreHookFor(RECEIVER, 2L, 5_000_000L, "")
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(50 * ONE_HBAR)
                        .via("nftWithHooksTxn"),
                sourcingContextual(spec -> {
                    final long tinybarGasCost =
                            5_000_000L * 2 * spec.ratesProvider().currentTinybarGasPrice();
                    final double usdGasCost = spec.ratesProvider().toUsdWithActiveRates(tinybarGasCost);
                    return validateChargedUsd(
                            "nftWithHooksTxn", NFT_TRANSFER_BASE_USD + 2 * HOOK_INVOCATION_USD + usdGasCost);
                }));
    }

    private static ByteString metadata(final int idNumber) {
        return copyFromUtf8("metadata" + idNumber);
    }
}

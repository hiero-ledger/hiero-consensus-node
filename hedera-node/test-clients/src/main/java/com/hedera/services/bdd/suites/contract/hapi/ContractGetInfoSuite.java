// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hapi;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.approxChangeFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sendModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withTargetLedgerId;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedQueryIds;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TINY_PARTS_PER_WHOLE;
import static com.hedera.services.bdd.suites.contract.precompile.CreatePrecompileSuite.MEMO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REVERTED_SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class ContractGetInfoSuite {
    private static final String NON_EXISTING_CONTRACT =
            HapiSpecSetup.getDefaultInstance().invalidContractName();

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        final var contract = "Multipurpose";
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract).entityMemo(MEMO).autoRenewSecs(6999999L),
                sendModified(withSuccessivelyVariedQueryIds(), () -> getContractInfo(contract)));
    }

    @HapiTest
    final Stream<DynamicTest> getInfoWorks() {
        final var contract = "Multipurpose";
        final var MEMO = "This is a test.";
        final var canonicalUsdPrice = 0.0001;
        final var canonicalQueryFeeAtActiveRate = new AtomicLong();
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_HUNDRED_HBARS),
                balanceSnapshot("beforeQuery", CIVILIAN_PAYER),
                uploadInitCode(contract),
                // refuse eth conversion because ethereum transaction is missing admin key and memo is same as
                // parent
                contractCreate(contract)
                        .adminKey("adminKey")
                        .entityMemo(MEMO)
                        .autoRenewSecs(6999999L)
                        .refusingEthConversion(),
                withOpContext((spec, opLog) -> canonicalQueryFeeAtActiveRate.set(spec.ratesProvider()
                        .toTbWithActiveRates((long) (canonicalUsdPrice * 100 * TINY_PARTS_PER_WHOLE)))),
                withTargetLedgerId(ledgerId -> getContractInfo(contract)
                        .payingWith(CIVILIAN_PAYER)
                        .hasEncodedLedgerId(ledgerId)
                        .hasExpectedInfo()
                        .has(contractWith().memo(MEMO).adminKey("adminKey"))),
                // Wait for the query payment transaction to be handled
                sleepFor(5_000),
                sourcing(() -> getAccountBalance(CIVILIAN_PAYER)
                        .hasTinyBars(
                                // Just sanity-check a fee within 50% of the canonical fee to be safe
                                approxChangeFromSnapshot(
                                        "beforeQuery",
                                        -canonicalQueryFeeAtActiveRate.get(),
                                        canonicalQueryFeeAtActiveRate.get() / 2))));
    }

    @HapiTest
    final Stream<DynamicTest> invalidContractFromCostAnswer() {
        return hapiTest(
                getContractInfo(NON_EXISTING_CONTRACT).hasCostAnswerPrecheck(ResponseCodeEnum.INVALID_CONTRACT_ID));
    }

    @HapiTest
    final Stream<DynamicTest> invalidContractFromAnswerOnly() {
        return hapiTest(getContractInfo(NON_EXISTING_CONTRACT)
                .nodePayment(27_159_182L)
                .hasAnswerOnlyPrecheck(ResponseCodeEnum.INVALID_CONTRACT_ID));
    }

    @HapiTest
    @DisplayName("Inner txs payer/signer gets charged for all gas used after INNER_TRANSACTION_FAILED")
    public Stream<DynamicTest> userPaysTheGasUsed() {
        final var batchOperator = "batchOperator";
        final var payer = "payer";

        return hapiTest(
                uploadInitCode("Multipurpose"),
                contractCreate("Multipurpose").gas(2_000_000L),
                cryptoCreate(payer).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                atomicBatch(
                                contractCall("Multipurpose", "believeIn", 1L)
                                        .gas(32_000L)
                                        .payingWith(payer)
                                        .signingWith(payer)
                                        .batchKey(batchOperator)
                                        .hasKnownStatus(REVERTED_SUCCESS),
                                contractCall("Multipurpose", "believeIn", 2L)
                                        .gas(32_000L)
                                        .payingWith(payer)
                                        .signingWith(payer)
                                        .batchKey(batchOperator)
                                        .hasKnownStatus(REVERTED_SUCCESS),
                                contractCall("Multipurpose", "believeIn", 3L)
                                        .gas(24_000L)
                                        .payingWith(payer)
                                        .signingWith(payer)
                                        .batchKey(batchOperator)
                                        .hasKnownStatus(INSUFFICIENT_GAS))
                        .payingWith(batchOperator)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getAccountBalance(payer).hasTinyBars(spec -> actual -> {
                    final long expected = ONE_HUNDRED_HBARS
                            - ((2 * 32_000L + 24_000L) * spec.ratesProvider().currentTinybarGasPrice());
                    try {
                        assertEquals(expected, actual, "Balance did not reflect gas used");
                    } catch (Throwable t) {
                        return Optional.of(t.getMessage());
                    }
                    return Optional.empty();
                }));
    }
}

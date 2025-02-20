// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hapi;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.approxChangeFromSnapshot;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sendModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedQueryIds;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TINY_PARTS_PER_WHOLE;
import static com.hedera.services.bdd.suites.contract.Utils.getResourcePath;
import static com.hedera.services.bdd.suites.contract.precompile.CreatePrecompileSuite.MEMO;

import com.google.common.io.Files;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class ContractGetBytecodeSuite {

    private static final Logger log = LogManager.getLogger(ContractGetBytecodeSuite.class);
    private static final String NON_EXISTING_CONTRACT =
            HapiSpecSetup.getDefaultInstance().invalidContractName();

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        final var contract = "Multipurpose";
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract).entityMemo(MEMO).autoRenewSecs(6999999L),
                sendModified(withSuccessivelyVariedQueryIds(), () -> getContractBytecode(contract)));
    }

    @HapiTest
    final Stream<DynamicTest> getByteCodeWorks() {
        final var contract = "EmptyConstructor";
        final var canonicalUsdFee = 0.05;
        final var canonicalQueryFeeAtActiveRate = new AtomicLong();
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_HUNDRED_HBARS),
                uploadInitCode(contract),
                contractCreate(contract),
                balanceSnapshot("beforeQuery", CIVILIAN_PAYER),
                withOpContext((spec, opLog) -> {
                    final var getBytecode = getContractBytecode(contract)
                            .payingWith(CIVILIAN_PAYER)
                            .saveResultTo("contractByteCode")
                            .exposingBytecodeTo(bytes -> {
                                canonicalQueryFeeAtActiveRate.set(spec.ratesProvider()
                                        .toTbWithActiveRates((long) (canonicalUsdFee * 100 * TINY_PARTS_PER_WHOLE)));
                                log.info(
                                        "Canoncal tinybar cost at active rate: {}",
                                        canonicalQueryFeeAtActiveRate.get());
                            });
                    allRunFor(spec, getBytecode);

                    @SuppressWarnings("UnstableApiUsage")
                    final var originalBytecode =
                            Hex.decode(Files.toByteArray(new File(getResourcePath(contract, ".bin"))));
                    final var actualBytecode = spec.registry().getBytes("contractByteCode");
                    // The original bytecode is modified on deployment
                    final var expectedBytecode = Arrays.copyOfRange(originalBytecode, 29, originalBytecode.length);
                    Assertions.assertArrayEquals(expectedBytecode, actualBytecode);
                }),
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
                getContractBytecode(NON_EXISTING_CONTRACT).hasCostAnswerPrecheck(ResponseCodeEnum.INVALID_CONTRACT_ID));
    }

    @HapiTest
    final Stream<DynamicTest> invalidContractFromAnswerOnly() {
        return hapiTest(getContractBytecode(NON_EXISTING_CONTRACT)
                .nodePayment(27_159_182L)
                .hasAnswerOnlyPrecheck(ResponseCodeEnum.INVALID_CONTRACT_ID));
    }
}

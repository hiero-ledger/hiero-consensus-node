// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hapi;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sendModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateNonZeroNodePaymentForQuery;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withTargetLedgerId;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedQueryIds;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.precompile.CreatePrecompileSuite.MEMO;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.stream.Stream;
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
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_HUNDRED_HBARS),
                uploadInitCode(contract),
                // refuse eth conversion because ethereum transaction is missing admin key and memo is same as
                // parent
                contractCreate(contract)
                        .adminKey("adminKey")
                        .entityMemo(MEMO)
                        .autoRenewSecs(6999999L)
                        .refusingEthConversion(),
                withTargetLedgerId(ledgerId -> getContractInfo(contract)
                        .payingWith(CIVILIAN_PAYER)
                        .hasEncodedLedgerId(ledgerId)
                        .hasExpectedInfo()
                        .has(contractWith().memo(MEMO).adminKey("adminKey"))
                        .via("queryRecord")),
                validateNonZeroNodePaymentForQuery("queryRecord"));
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
}

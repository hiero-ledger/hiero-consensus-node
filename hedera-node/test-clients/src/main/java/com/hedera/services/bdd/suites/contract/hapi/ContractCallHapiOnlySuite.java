// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hapi;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.keys.KeyFactory.KeyType.THRESHOLD;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_NEGATIVE_VALUE;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Test suite for contract call tests that are valid _only_ as HAPI contract calls, not as
 * Ethereum transactions.
 */
@Tag(SMART_CONTRACT)
public class ContractCallHapiOnlySuite {

    public static final String TOKEN = "yahcliToken";
    private static final long DEPOSIT_AMOUNT = 1000;
    public static final String PAY_RECEIVABLE_CONTRACT = "PayReceivable";
    private static final String PAY_TXN = "payTxn";

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> callFailsWhenAmountIsNegativeButStillChargedFee() {
        final var payer = "payer";
        return hapiTest(
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                contractCreate(PAY_RECEIVABLE_CONTRACT)
                        .adminKey(THRESHOLD)
                        .gas(1_000_000)
                        .refusingEthConversion(),
                cryptoCreate(payer).balance(ONE_MILLION_HBARS).payingWith(GENESIS),
                withOpContext((spec, ignore) -> {
                    final var subop1 = balanceSnapshot("balanceBefore0", payer);
                    final var subop2 = contractCall(PAY_RECEIVABLE_CONTRACT)
                            .via(PAY_TXN)
                            .payingWith(payer)
                            .sending(-DEPOSIT_AMOUNT)
                            .hasKnownStatus(CONTRACT_NEGATIVE_VALUE)
                            .refusingEthConversion();
                    final var subop3 = getTxnRecord(PAY_TXN).logged();
                    allRunFor(spec, subop1, subop2, subop3);
                    final var delta = subop3.getResponseRecord()
                            .getTransferList()
                            .getAccountAmounts(0)
                            .getAmount();
                    final var subop4 =
                            getAccountBalance(payer).hasTinyBars(changeFromSnapshot("balanceBefore0", -delta));
                    allRunFor(spec, subop4);
                }));
    }
}

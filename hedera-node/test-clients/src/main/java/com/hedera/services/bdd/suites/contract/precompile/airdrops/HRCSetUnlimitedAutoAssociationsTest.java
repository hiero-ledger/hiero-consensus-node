// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.airdrops;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hederahashgraph.api.proto.java.AccountID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class HRCSetUnlimitedAutoAssociationsTest {

    @HapiTest
    public Stream<DynamicTest> hrcSetUnlimitedAutoAssociations() {
        final AtomicReference<AccountID> accountNum = new AtomicReference<>();
        return hapiTest(
                cryptoCreate("account")
                        .balance(100 * ONE_HUNDRED_HBARS)
                        .maxAutomaticTokenAssociations(0)
                        .exposingCreatedIdTo(accountNum::set),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCallWithFunctionAbi(
                                        String.valueOf(accountNum.get().getAccountNum()),
                                        getABIFor(
                                                com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION,
                                                "setUnlimitedAutomaticAssociations",
                                                "HRC904"),
                                        true)
                                .via("setUnlimitedAutoAssociations")
                                .payingWith("account")
                                .gas(1_000_000L),
                        getTxnRecord("setUnlimitedAutoAssociations")
                                .logged()
                                .hasPriority(recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .resultThruAbi(
                                                        getABIFor(
                                                                com.hedera.services.bdd.suites.contract.Utils
                                                                        .FunctionType.FUNCTION,
                                                                "setUnlimitedAutomaticAssociations",
                                                                "HRC904"),
                                                        isLiteralResult(new Object[] {Long.valueOf(22)})))),
                        getAccountInfo("account").hasMaxAutomaticAssociations(-1))));
    }

    @HapiTest
    public Stream<DynamicTest> hrcSetDisabledAutoAssociations() {
        final AtomicReference<AccountID> accountNum = new AtomicReference<>();
        return hapiTest(
                cryptoCreate("account")
                        .balance(100 * ONE_HUNDRED_HBARS)
                        .maxAutomaticTokenAssociations(10)
                        .exposingCreatedIdTo(accountNum::set),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCallWithFunctionAbi(
                                        String.valueOf(accountNum.get().getAccountNum()),
                                        getABIFor(
                                                com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION,
                                                "setUnlimitedAutomaticAssociations",
                                                "HRC904"),
                                        false)
                                .via("setUnlimitedAutoAssociations")
                                .payingWith("account")
                                .gas(1_000_000L),
                        getTxnRecord("setUnlimitedAutoAssociations")
                                .logged()
                                .hasPriority(recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .resultThruAbi(
                                                        getABIFor(
                                                                com.hedera.services.bdd.suites.contract.Utils
                                                                        .FunctionType.FUNCTION,
                                                                "setUnlimitedAutomaticAssociations",
                                                                "HRC904"),
                                                        isLiteralResult(new Object[] {Long.valueOf(22)})))),
                        getAccountInfo("account").hasMaxAutomaticAssociations(0))));
    }
}

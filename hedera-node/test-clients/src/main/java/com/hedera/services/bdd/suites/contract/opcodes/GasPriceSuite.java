// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.opcodes;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.suites.utils.contracts.SimpleBytesResult.bigIntResult;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class GasPriceSuite {

    @Contract(contract = "GasPriceContract", creationGas = 8_000_000L)
    static SpecContract gasPriceContract;

    @HapiTest
    @Tag(MATS)
    public Stream<DynamicTest> getGasPrice() {
        return hapiTest(gasPriceContract
                .call("getTxGasPrice")
                .gas(100_000L)
                .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS))
                .andAssert(txn ->
                        txn.hasResults(ContractFnResultAsserts.resultWith().contractCallResult(bigIntResult(71)))));
    }

    @HapiTest
    @Tag(MATS)
    public Stream<DynamicTest> getLastGasPrice() {
        return hapiTest(
                gasPriceContract
                        .call("getLastTxGasPrice")
                        .gas(100_000L)
                        .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS))
                        .andAssert(txn -> txn.hasResults(
                                ContractFnResultAsserts.resultWith().contractCallResult(bigIntResult(0)))),
                gasPriceContract
                        .call("updateGasPrice")
                        .gas(100_000L)
                        .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS)),
                gasPriceContract
                        .call("getLastTxGasPrice")
                        .gas(100_000L)
                        .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS))
                        .andAssert(txn -> txn.hasResults(
                                ContractFnResultAsserts.resultWith().contractCallResult(bigIntResult(71)))));
    }
}

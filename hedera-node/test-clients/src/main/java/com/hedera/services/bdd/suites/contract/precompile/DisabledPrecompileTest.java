// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
@OrderedInIsolation
public class DisabledPrecompileTest {
    @Contract(contract = "PrecompileCaller", creationGas = 4_000_000L, isImmutable = true)
    static SpecContract contract;

    @FungibleToken(name = "fungibleToken")
    static SpecFungibleToken ft;

    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "atomicBatch.isEnabled",
                "true",
                "atomicBatch.maxNumberOfTransactions",
                "50",
                "contracts.throttle.throttleByGas",
                "false"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    @DisplayName("Calling a disabled precompile reverts")
    public Stream<DynamicTest> callDisabledPrecompile() {
        return hapiTest(
                overriding("contracts.precompile.disabled", "2"),
                contract.call("callSha256AndIsToken", "submit".getBytes(), ft)
                        .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
    }

    @HapiTest
    @DisplayName("Atomic calling a disabled precompile reverts")
    public Stream<DynamicTest> atomicCallDisabledPrecompile() {
        return hapiTest(
                overriding("contracts.precompile.disabled", "2"),
                contract.call("callSha256AndIsToken", "submit".getBytes(), ft)
                        .wrappedInBatchOperation(BATCH_OPERATOR, OK, INNER_TRANSACTION_FAILED)
                        .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
    }

    @HapiTest
    @DisplayName("Calling a enabled precompile is successful")
    public Stream<DynamicTest> callEnabledPrecompile() {
        return hapiTest(
                overriding("contracts.precompile.disabled", ""),
                contract.call("callSha256AndIsToken", "submit".getBytes(), ft)
                        .andAssert(txn -> txn.hasKnownStatus(SUCCESS)));
    }
}

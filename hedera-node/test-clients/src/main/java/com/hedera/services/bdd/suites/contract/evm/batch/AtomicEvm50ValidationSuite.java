// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.evm.batch;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_EXECUTION_EXCEPTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of Evm50ValidationSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@Tag(SMART_CONTRACT)
class AtomicEvm50ValidationSuite {

    private static final String Module05OpcodesExist_CONTRACT = "Module050OpcodesExist";
    private static final long A_BUNCH_OF_GAS = 500_000L;
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @LeakyHapiTest(overrides = {"contracts.evm.version"})
    final Stream<DynamicTest> verifiesNonExistenceForV50OpcodesInV46() {
        final var contract = Module05OpcodesExist_CONTRACT;
        return hapiTest(
                overriding("contracts.evm.version", "v0.46"),
                uploadInitCode(contract),
                contractCreate(contract),
                atomicBatch(contractCall(contract, "try_transient_storage")
                                .gas(A_BUNCH_OF_GAS)
                                .hasKnownStatus(CONTRACT_EXECUTION_EXCEPTION)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractCall(contract, "try_mcopy")
                                .gas(A_BUNCH_OF_GAS)
                                .hasKnownStatus(CONTRACT_EXECUTION_EXCEPTION)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractCall(contract, "try_kzg_precompile")
                                .hasKnownStatus(CONTRACT_EXECUTION_EXCEPTION)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> verifiesExistenceOfV050Opcodes() {
        final var contract = Module05OpcodesExist_CONTRACT;
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                atomicBatch(
                                contractCall(contract, "try_transient_storage")
                                        .gas(A_BUNCH_OF_GAS)
                                        .batchKey(BATCH_OPERATOR),
                                contractCall(contract, "try_mcopy")
                                        .gas(A_BUNCH_OF_GAS)
                                        .batchKey(BATCH_OPERATOR),
                                contractCall(contract, "try_kzg_precompile")
                                        .gas(A_BUNCH_OF_GAS)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }
}

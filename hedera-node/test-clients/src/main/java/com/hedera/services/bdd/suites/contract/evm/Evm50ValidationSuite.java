// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.evm;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@OrderedInIsolation
public class Evm50ValidationSuite {
    private static final String Module05OpcodesExist_CONTRACT = "Module050OpcodesExist";
    private static final long A_BUNCH_OF_GAS = 500_000L;

    @HapiTest
    final Stream<DynamicTest> verifiesExistenceOfV050Opcodes() {
        final var contract = Module05OpcodesExist_CONTRACT;
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                contractCall(contract, "try_transient_storage").gas(A_BUNCH_OF_GAS),
                contractCall(contract, "try_mcopy").gas(A_BUNCH_OF_GAS),
                contractCall(contract, "try_kzg_precompile").gas(A_BUNCH_OF_GAS));
    }
}

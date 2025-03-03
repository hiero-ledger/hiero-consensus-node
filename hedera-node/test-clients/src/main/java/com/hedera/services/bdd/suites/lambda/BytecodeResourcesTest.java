// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.lambda;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.suites.contract.Utils.BYTECODE_EXTENSION;
import static com.hedera.services.bdd.suites.contract.Utils.CONTRACT_RESOURCE_PATH;
import static com.hedera.services.bdd.suites.contract.Utils.DEFAULT_LAMBDAS_ROOT;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

// @Disabled
public class BytecodeResourcesTest {
    @HapiTest
    final Stream<DynamicTest> writePreInitializedBytecode(
            @Contract(contract = "OneTimeCodeTransferAllowance", implementsLambda = true) SpecContract contract) {
        return hapiTest(contract.getBytecode()
                .andAssert(op -> op.exposingBytecodeTo(bytes -> {
                    final var loc = String.format(
                            CONTRACT_RESOURCE_PATH,
                            DEFAULT_LAMBDAS_ROOT,
                            "OneTimeCodeTransferAllowance",
                            BYTECODE_EXTENSION);
                    try {
                        Files.write(Paths.get(loc), bytes);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })));
    }
}

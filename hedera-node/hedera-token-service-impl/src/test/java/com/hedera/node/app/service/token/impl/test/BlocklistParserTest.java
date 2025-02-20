// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import com.hedera.node.app.service.token.impl.BlocklistParser;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.assertj.core.api.Assertions;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BlocklistParserTest {

    private static final String BASE_TEST_FILE_DIR = "blocklist-parsing/";

    private BlocklistParser subject;

    @BeforeEach
    void setup() {
        subject = new BlocklistParser();
    }

    @CsvSource({
        BASE_TEST_FILE_DIR + "non-csv.txt,", // Not a CSV file
        BASE_TEST_FILE_DIR + "empty.csv,", // An empty file
        BASE_TEST_FILE_DIR + "this-file-doesnt-exist.csv,", // This file doesn't actually exist
        BASE_TEST_FILE_DIR + "partially-valid-evm-addresses-blocklist.csv" // Partially valid CSV file
    })
    @ParameterizedTest
    void parseInvalidFile(final String filename) {
        final var result = subject.parse(filename);
        Assertions.assertThat(result).isEmpty();
    }

    @Test
    void parseCsvFileWithNoHeader() {
        final var result = subject.parse(BASE_TEST_FILE_DIR + "no-header-test-evm-addresses-blocklist.csv");
        // Even though there are two entries in the test file, the code assumes that the first line is a header line
        Assertions.assertThat(result).hasSize(1);
    }

    @Test
    void parseValidFile() {
        final var result = subject.parse(BASE_TEST_FILE_DIR + "test-evm-addresses-blocklist.csv");
        Assertions.assertThat(result).hasSize(6);
        Assertions.assertThat(result)
                .extracting("evmAddress", "memo")
                .containsExactly(
                        Tuple.tuple(
                                Bytes.fromHex("e261e26aecce52b3788fac9625896ffbc6bb4424"), "Hedera Local Node address"),
                        Tuple.tuple(
                                Bytes.fromHex("ce16e8eb8f4bf2e65ba9536c07e305b912bafacf"), "Hedera Local Node address"),
                        Tuple.tuple(Bytes.fromHex("f39fd6e51aad88f6f4ce6ab8827279cfffb92266"), "Hardhat address"),
                        Tuple.tuple(Bytes.fromHex("70997970c51812dc3a010c7d01b50e0d17dc79c8"), "Hardhat address"),
                        Tuple.tuple(Bytes.fromHex("7e5f4552091a69125d5dfcb7b8c2659029395bdf"), "Hardhat test account"),
                        Tuple.tuple(Bytes.fromHex("a04a864273e77be6fe500ad2f5fad320d9168bb6"), "Hardhat test account"));
    }
}

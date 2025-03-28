// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_APPEND;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ACCOUNT_WIPE;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.hapi.node.base.SubType.SCHEDULE_CREATE_CONTRACT_CALL;
import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;
import static com.hedera.node.app.hapi.fees.pricing.ScheduleGenerator.SUPPORTED_FUNCTIONS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

class ScheduleGeneratorTest {
    private static final String EXPECTED_SCHEDULES_LOC = "src/test/resources/expectedFeeSchedules.json";
    private static final String ALL_SUPPORTED_SCHEDULES_LOC = "src/test/resources/supportedFeeSchedules.json";

    private final ScheduleGenerator subject = new ScheduleGenerator();

    @Test
    void canGenerateAllSupportedSchedules() throws IOException {
        final var file = Paths.get(ALL_SUPPORTED_SCHEDULES_LOC);
        assertDoesNotThrow(() -> {
            final var allSupportedSchedules = subject.feeSchedulesFor(SUPPORTED_FUNCTIONS);
            Files.writeString(file, allSupportedSchedules);
        });
        Files.delete(file);
    }

    @Test
    void generatesExpectedSchedules() throws IOException {
        final var om = new ObjectMapper();

        final var expected = om.readValue(Files.readString(Paths.get(EXPECTED_SCHEDULES_LOC)), List.class);

        final var actual = om.readValue(subject.feeSchedulesFor(MISC_TEST_FUNCTIONS), List.class);

        assertEquals(expected, actual);
    }

    private static final List<Pair<HederaFunctionality, List<SubType>>> MISC_TEST_FUNCTIONS = List.of(
            /* Crypto */
            Pair.of(
                    CRYPTO_TRANSFER,
                    List.of(
                            DEFAULT,
                            TOKEN_FUNGIBLE_COMMON,
                            TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES,
                            TOKEN_NON_FUNGIBLE_UNIQUE,
                            TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES)),
            /* File */
            Pair.of(FILE_APPEND, List.of(DEFAULT)),
            /* Token */
            Pair.of(TOKEN_ACCOUNT_WIPE, List.of(TOKEN_NON_FUNGIBLE_UNIQUE)),
            /* Consensus */
            Pair.of(CONSENSUS_SUBMIT_MESSAGE, List.of(DEFAULT)),
            Pair.of(SCHEDULE_CREATE, List.of(DEFAULT, SCHEDULE_CREATE_CONTRACT_CALL)));
}

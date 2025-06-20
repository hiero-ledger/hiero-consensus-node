// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

import static com.hedera.node.app.hapi.fees.pricing.ScheduleGenerator.SUPPORTED_FUNCTIONS;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusSubmitMessage;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileAppend;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ScheduleCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenAccountWipe;
import static com.hederahashgraph.api.proto.java.SubType.DEFAULT;
import static com.hederahashgraph.api.proto.java.SubType.SCHEDULE_CREATE_CONTRACT_CALL;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.SubType;
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
                    CryptoTransfer,
                    List.of(
                            DEFAULT,
                            TOKEN_FUNGIBLE_COMMON,
                            TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES,
                            TOKEN_NON_FUNGIBLE_UNIQUE,
                            TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES)),
            /* File */
            Pair.of(FileAppend, List.of(DEFAULT)),
            /* Token */
            Pair.of(TokenAccountWipe, List.of(TOKEN_NON_FUNGIBLE_UNIQUE)),
            /* Consensus */
            Pair.of(ConsensusSubmitMessage, List.of(DEFAULT)),
            Pair.of(ScheduleCreate, List.of(DEFAULT, SCHEDULE_CREATE_CONTRACT_CALL)));
}

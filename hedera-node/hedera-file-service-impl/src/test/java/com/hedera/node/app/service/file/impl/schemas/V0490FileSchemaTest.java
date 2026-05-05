// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.schemas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.Test;

class V0490FileSchemaTest {

    @Test
    void parseSimpleFeesSchedules_withValidJson_returnsFeeSchedule() throws IOException {
        final var resourceStream =
                V0490FileSchemaTest.class.getClassLoader().getResourceAsStream("genesis/simpleFeesSchedules.json");
        assertThat(resourceStream).isNotNull();
        final var jsonBytes = resourceStream.readAllBytes();

        final FeeSchedule result = V0490FileSchema.parseSimpleFeesSchedules(jsonBytes);

        assertThat(result).isNotNull();
        assertThat(result.extras()).isNotEmpty();
        assertThat(result.hasNode()).isTrue();
        assertThat(result.hasNetwork()).isTrue();
    }

    @Test
    void parseSimpleFeesSchedules_withInvalidJson_throwsIllegalArgumentException() {
        final var invalidJson = "not valid json".getBytes();

        assertThatThrownBy(() -> V0490FileSchema.parseSimpleFeesSchedules(invalidJson))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unable to parse simple fee schedule file");
    }
}

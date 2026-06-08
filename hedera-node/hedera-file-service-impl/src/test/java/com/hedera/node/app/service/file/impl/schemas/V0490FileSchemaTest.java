// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.schemas;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.Test;

class V0490FileSchemaTest {

    @Test
    void parseSimpleFeesSchedules_withValidJson_returnsFeeSchedule() throws IOException {
        try (final InputStream resourceStream = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("genesis/simpleFeesSchedules.json")) {
            assertThat(resourceStream).isNotNull();
            final byte[] jsonBytes = resourceStream.readAllBytes();

            final FeeSchedule result = V0490FileSchema.parseSimpleFeesSchedules(jsonBytes);

            assertThat(result).isNotNull();
            assertThat(result.extras()).isNotEmpty();
            assertThat(result.hasNode()).isTrue();
            assertThat(result.hasNetwork()).isTrue();
        }
    }

    @Test
    void parseSimpleFeesSchedules_withInvalidJson_throwsIllegalArgumentException() {
        final byte[] invalidJson = "not valid json".getBytes(UTF_8);

        assertThatThrownBy(() -> V0490FileSchema.parseSimpleFeesSchedules(invalidJson))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unable to parse simple fee schedule file");
    }
}

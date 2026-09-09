// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.schemas;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.platform.state.SingletonType;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.Test;

class V0490FileSchemaTest {

    @Test
    void upgradeDataStateKeyIsLocaleIndependent() {
        final var original = Locale.getDefault();
        try {
            for (final var tag : new String[] {"en-US", "tr-TR", "az", "fa-IR", "bn-BD", "th-TH-u-nu-thai"}) {
                Locale.setDefault(Locale.forLanguageTag(tag));

                final var stateKey = V0490FileSchema.upgradeDataStateKey(150L);

                assertThat(stateKey).as("locale %s", tag).isEqualTo("FILESERVICE_I_UPGRADE_DATA_150");
                assertThat(SingletonType.valueOf(stateKey)).isEqualTo(SingletonType.FILESERVICE_I_UPGRADE_DATA_150);
            }
        } finally {
            Locale.setDefault(original);
        }
    }

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

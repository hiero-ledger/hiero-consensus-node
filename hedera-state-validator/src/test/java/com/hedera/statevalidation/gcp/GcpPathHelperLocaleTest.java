// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.gcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.LOCALE)
class GcpPathHelperLocaleTest {

    /** Arabic (Egypt) renders {@code %d} with Arabic-Indic digits. */
    private static final Locale ARABIC_DIGITS = Locale.forLanguageTag("ar-EG");

    private static final String EXPECTED_NAME = "000000000000000000000000000000382065.blk.gz";

    private final Locale originalDefault = Locale.getDefault();

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(originalDefault);
    }

    @Test
    @DisplayName("blockFileName() zero-pads with ASCII digits under a locale with non-ASCII digits")
    void blockFileNameUsesAsciiDigitsUnderAnyDefaultLocale() {
        Locale.setDefault(ARABIC_DIGITS);

        assertThat(GcpPathHelper.blockFileName(382_065L)).isEqualTo(EXPECTED_NAME);
    }

    @Test
    @DisplayName("blockFileUri() builds the same object name under any default locale")
    void blockFileUriIsIndependentOfTheDefaultLocale() {
        Locale.setDefault(Locale.US);
        final String underUs = GcpPathHelper.blockFileUri("gs://bucket/blocks", 382_065L);

        Locale.setDefault(ARABIC_DIGITS);

        assertThat(GcpPathHelper.blockFileUri("gs://bucket/blocks", 382_065L))
                .isEqualTo(underUs)
                .isEqualTo("gs://bucket/blocks/" + EXPECTED_NAME);
    }
}

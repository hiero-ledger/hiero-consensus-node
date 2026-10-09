// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.LOCALE)
class ValidationExceptionLocaleTest {

    /** Arabic (Egypt) is the strictest readily available locale for number and text formatting. */
    private static final Locale ARABIC_DIGITS = Locale.forLanguageTag("ar-EG");

    private final Locale originalDefault = Locale.getDefault();

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(originalDefault);
    }

    @Test
    @DisplayName("the message is assembled the same way under any default locale")
    void messageIsIndependentOfTheDefaultLocale() {
        Locale.setDefault(ARABIC_DIGITS);

        assertThat(new ValidationException("entityIdUniqueness", "Expected <0> but was <3>"))
                .hasMessage("[entityIdUniqueness] Validation failed: Expected <0> but was <3>");
        assertThat(new ValidationException("entityIdUniqueness", "reading leaf 42", new RuntimeException("boom")))
                .hasMessage("[entityIdUniqueness] Validation failed at: reading leaf 42")
                .hasRootCauseMessage("boom");
    }
}

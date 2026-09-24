// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.LOCALE)
class StorageReportLocaleTest {

    /**
     * Arabic (Egypt) renders {@code %d}, {@code %,d} and {@code %.2f} with Arabic-Indic digits and
     * non-ASCII separators, so it detects any conversion left to the JVM default locale.
     */
    private static final Locale ARABIC_DIGITS = Locale.forLanguageTag("ar-EG");

    private final Locale originalDefault = Locale.getDefault();

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(originalDefault);
    }

    @Test
    @DisplayName("toString() renders the same ASCII numbers under a locale with non-ASCII digits")
    void toStringIsIndependentOfTheDefaultLocale() {
        final var report = reportWithAllFieldsSet();

        Locale.setDefault(Locale.US);
        final String underUs = report.toString();

        Locale.setDefault(ARABIC_DIGITS);
        final String underArabicDigits = report.toString();

        assertThat(underArabicDigits).isEqualTo(underUs);
    }

    @Test
    @DisplayName("toString() renders ASCII digits and separators under a locale with non-ASCII digits")
    void toStringRendersAsciiNumbersUnderAnyDefaultLocale() {
        Locale.setDefault(ARABIC_DIGITS);

        assertThat(reportWithAllFieldsSet().toString()).isEqualTo("""
                          Key Range: 1 to 2000000
                          Size: 1234 MB
                          Files: 12
                          Items: 1,234,567
                          Waste: 12.35%
                          Duplicates: 7,654
                        """);
    }

    private static StorageReport reportWithAllFieldsSet() {
        final var report = new StorageReport();
        report.setMinKey(1);
        report.setMaxKey(2_000_000);
        report.setOnDiskSizeInMb(1234);
        report.setNumberOfStorageFiles(12);
        report.setItemCount(1_234_567);
        report.setWastePercentage(12.3456);
        report.setDuplicateItems(7654);
        return report;
    }
}

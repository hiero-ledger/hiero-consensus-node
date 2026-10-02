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
class StateReportLocaleTest {

    /** Arabic (Egypt) renders {@code %d}, {@code %,d} and {@code %.2f} with Arabic-Indic digits. */
    private static final Locale ARABIC_DIGITS = Locale.forLanguageTag("ar-EG");

    private final Locale originalDefault = Locale.getDefault();

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(originalDefault);
    }

    @Test
    @DisplayName("toString() renders the same ASCII numbers under a locale with non-ASCII digits")
    void toStringIsIndependentOfTheDefaultLocale() {
        final var report = new StateReport();
        report.setNodeName("node0");
        report.setPathToHashReport(storageReport());
        report.setKeyToPathReport(storageReport());
        report.setPathToKeyValueReport(storageReport());

        Locale.setDefault(Locale.US);
        final String underUs = report.toString();

        Locale.setDefault(ARABIC_DIGITS);

        assertThat(report.toString())
                .isEqualTo(underUs)
                .startsWith("Report for node: node0")
                .contains("  Items: 1,234,567", "  Waste: 12.35%");
    }

    private static StorageReport storageReport() {
        final var report = new StorageReport();
        report.setMinKey(1);
        report.setMaxKey(2_000_000);
        report.setOnDiskSizeInMb(1234);
        report.setNumberOfStorageFiles(12);
        report.setItemCount(1_234_567);
        report.setWastePercentage(12.3456);
        return report;
    }
}

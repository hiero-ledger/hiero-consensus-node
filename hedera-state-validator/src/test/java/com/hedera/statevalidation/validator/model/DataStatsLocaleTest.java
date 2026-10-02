// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.LOCALE)
class DataStatsLocaleTest {

    /** Arabic (Egypt) renders {@code %,d} with Arabic-Indic digits and a non-ASCII group separator. */
    private static final Locale ARABIC_DIGITS = Locale.forLanguageTag("ar-EG");

    private final Locale originalDefault = Locale.getDefault();

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(originalDefault);
    }

    @Test
    @DisplayName("toString() renders the same ASCII numbers under a locale with non-ASCII digits")
    void toStringIsIndependentOfTheDefaultLocale() {
        final var stats = statsWithCountedItems();

        Locale.setDefault(Locale.US);
        final String underUs = stats.toString();

        Locale.setDefault(ARABIC_DIGITS);

        assertThat(stats.toString()).isEqualTo(underUs).contains("Total items: 3", "Total space: 3,000 bytes");
    }

    @Test
    @DisplayName("toStringContent() renders ASCII group separators under a locale with non-ASCII digits")
    void toStringContentIsIndependentOfTheDefaultLocale() {
        final var stats = statsWithCountedItems();

        Locale.setDefault(Locale.US);
        final String underUs = stats.getP2kv().toStringContent();

        Locale.setDefault(ARABIC_DIGITS);

        assertThat(stats.getP2kv().toStringContent())
                .isEqualTo(underUs)
                .contains("Total items: 1", "Total space: 1,000 bytes", "Parse errors: 1");
    }

    private static DataStats statsWithCountedItems() {
        final var stats = new DataStats();
        for (final var group : new DataStats.StatGroup[] {stats.getP2kv(), stats.getId2c(), stats.getK2p()}) {
            group.incrementItemCount();
            group.addSpaceSize(1_000L);
            group.incrementObsoleteItemCount();
            group.addObsoleteSpaceSize(500L);
            group.incrementParseErrorCount();
        }
        return stats;
    }
}

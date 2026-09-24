// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateKey;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.LOCALE)
class StateUtilsLocaleTest {

    /**
     * Turkish uppercases {@code i} to the dotted {@code İ}, so {@code "TokenService"} becomes
     * {@code "TOKENSERVİCE"} and no longer matches any generated state-key enum constant.
     */
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    /** Arabic (Egypt) renders {@code %d} with Arabic-Indic digits. */
    private static final Locale ARABIC_DIGITS = Locale.forLanguageTag("ar-EG");

    private final Locale originalDefault = Locale.getDefault();

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(originalDefault);
    }

    @Test
    @DisplayName("stateIdFor() resolves a service name containing 'i' under the Turkish locale")
    void stateIdForResolvesDottedIServiceNameUnderTurkishLocale() {
        Locale.setDefault(Locale.US);
        final int underUs = StateUtils.stateIdFor("TokenService", "ACCOUNTS");

        Locale.setDefault(TURKISH);

        assertThat(StateUtils.stateIdFor("TokenService", "ACCOUNTS")).isEqualTo(underUs);
        assertThat(underUs).isEqualTo(StateKey.KeyOneOfType.TOKENSERVICE_I_ACCOUNTS.protoOrdinal());
    }

    @Test
    @DisplayName("stateIdFor() resolves a singleton whose service name contains 'i' under the Turkish locale")
    void stateIdForResolvesDottedISingletonUnderTurkishLocale() {
        Locale.setDefault(TURKISH);

        assertThat(StateUtils.stateIdFor("EntityIdService", "ENTITY_ID"))
                .isEqualTo(SingletonType.ENTITYIDSERVICE_I_ENTITY_ID.protoOrdinal());
    }

    @Test
    @DisplayName("stateIdFor() reports an unknown state with ASCII text under any default locale")
    void stateIdForReportsUnknownStateIndependentlyOfTheDefaultLocale() {
        Locale.setDefault(ARABIC_DIGITS);

        assertThatThrownBy(() -> StateUtils.stateIdFor("NoSuchService", "NO_SUCH_KEY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No state ID found for NoSuchService.NO_SUCH_KEY");
    }
}

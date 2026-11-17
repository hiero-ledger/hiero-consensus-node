// SPDX-License-Identifier: Apache-2.0
package com.hedera.hapi.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link HapiUtils#asReadableIp(Bytes)} and {@link HapiUtils#asAccountString(AccountID)} both render
 * identifiers that other components parse: the first becomes a gossip peer address, the second names
 * the record and block stream directories. Neither may depend on the JVM default locale.
 */
@ResourceLock(Resources.LOCALE)
final class HapiUtilsLocaleTest {

    private final Locale originalDefault = Locale.getDefault();

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(originalDefault);
    }

    @ParameterizedTest(name = "under the {0} locale")
    @ValueSource(strings = {"ar-EG", "fa-IR", "bn-IN", "th-TH-u-nu-thai", "my-MM", "tr-TR", "de-DE"})
    @DisplayName("asReadableIp() renders an address that InetAddress still resolves as a literal")
    void asReadableIpStaysParseableUnderAnyDefaultLocale(final String languageTag) throws UnknownHostException {
        Locale.setDefault(Locale.forLanguageTag(languageTag));

        final String address = HapiUtils.asReadableIp(Bytes.wrap(new byte[] {(byte) 192, (byte) 168, 1, 10}));

        assertThat(address).isEqualTo("192.168.1.10");
        assertThat(InetAddress.getByName(address).getHostAddress()).isEqualTo("192.168.1.10");
    }

    @ParameterizedTest(name = "under the {0} locale")
    @ValueSource(strings = {"ar-EG", "fa-IR", "bn-IN", "th-TH-u-nu-thai", "my-MM", "tr-TR", "de-DE"})
    @DisplayName("asAccountString() renders ASCII digits usable as a directory name")
    void asAccountStringUsesAsciiDigitsUnderAnyDefaultLocale(final String languageTag) {
        final var accountId =
                AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(3).build();

        Locale.setDefault(Locale.US);
        final String underUs = HapiUtils.asAccountString(accountId);

        Locale.setDefault(Locale.forLanguageTag(languageTag));

        assertThat(HapiUtils.asAccountString(accountId)).isEqualTo(underUs).isEqualTo("0.0.3");
    }
}

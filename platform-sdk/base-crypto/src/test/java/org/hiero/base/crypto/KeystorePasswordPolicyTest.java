// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(LogCaptureExtension.class)
class KeystorePasswordPolicyTest {

    @LoggingSubject
    private KeystorePasswordPolicy subject;

    @LoggingTarget
    private LogCaptor logCaptor;

    @Test
    void doesNothingWhenPasswordIsCompliant() {
        KeystorePasswordPolicy.warnIfNonCompliant("some.key", "Abcdefghijk1!");
        assertThat(logCaptor.warnLogs()).isEmpty();
    }

    @Test
    void warnsWhenPasswordIsNonCompliantAndNeverLogsPasswordValue() {
        final var password = "short";
        KeystorePasswordPolicy.warnIfNonCompliant("security.keystore.password", password);

        final var warningLogs = logCaptor.warnLogs();
        assertThat(warningLogs).hasSize(1);
        final var renderedMessage = warningLogs.get(0);
        assertThat(renderedMessage)
                .contains("does not meet recommended password policy")
                .contains("security.keystore.password")
                .contains("minLength>=12", "uppercase", "digit", "special")
                .doesNotContain(password);
    }

    @Test
    void reportsOnlyMissingCharacterClassesWhenLengthIsSufficient() {
        KeystorePasswordPolicy.warnIfNonCompliant("k", "abcdefghijk1");

        final var warningLogs = logCaptor.warnLogs();
        assertThat(warningLogs).hasSize(1);
        final var renderedMessage = warningLogs.get(0);
        assertThat(renderedMessage)
                .contains("uppercase", "special")
                .doesNotContain("minLength>=12")
                .doesNotContain("lowercase")
                .doesNotContain("digit");
    }

    @Test
    void treatsNonLetterNonDigitCharactersAsSpecial() {
        KeystorePasswordPolicy.warnIfNonCompliant("k", "Abcdefghijk1 ");
        assertThat(logCaptor.warnLogs()).isEmpty();
    }

    @Test
    void nullArgumentsThrowWithHelpfulMessages() {
        final var ex1 = org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class, () -> KeystorePasswordPolicy.warnIfNonCompliant(null, "p"));
        assertThat(ex1.getMessage()).isEqualTo("configKey must not be null");

        final var ex2 = org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class, () -> KeystorePasswordPolicy.warnIfNonCompliant("k", null));
        assertThat(ex2.getMessage()).isEqualTo("password must not be null");
    }

    @Test
    void issuesOrderIsStableAndMatchesExpected() {
        KeystorePasswordPolicy.warnIfNonCompliant("k", "ABCDEFGHIJ");

        final var warningLogs = logCaptor.warnLogs();
        assertThat(warningLogs).hasSize(1);
        final var renderedMessage = warningLogs.get(0);
        assertThat(renderedMessage)
                .contains(String.join(", ", List.of("minLength>=12", "lowercase", "digit", "special")));
    }

    @Test
    void canBeCalledRepeatedlyWithoutSideEffects() {
        for (int i = 0; i < 3; i++) {
            KeystorePasswordPolicy.warnIfNonCompliant("k", "short");
        }

        assertThat(logCaptor.warnLogs()).hasSize(3);
    }
}

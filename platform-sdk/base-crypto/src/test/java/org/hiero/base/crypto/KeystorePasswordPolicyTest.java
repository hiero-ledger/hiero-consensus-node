// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KeystorePasswordPolicyTest {

    @Test
    void doesNothingWhenPasswordIsCompliant() {
        final Logger logger = org.mockito.Mockito.mock(Logger.class);

        KeystorePasswordPolicy.warnIfNonCompliant(logger, "some.key", "Abcdefghijk1!");

        org.mockito.Mockito.verifyNoInteractions(logger);
    }

    @Test
    void warnsWhenPasswordIsNonCompliantAndNeverLogsPasswordValue() {
        final Logger logger = org.mockito.Mockito.mock(Logger.class);

        final var password = "short";
        KeystorePasswordPolicy.warnIfNonCompliant(logger, "security.keystore.password", password);

        final var messageCaptor = ArgumentCaptor.forClass(String.class);
        final var keyCaptor = ArgumentCaptor.forClass(Object.class);
        final var issuesCaptor = ArgumentCaptor.forClass(Object.class);

        org.mockito.Mockito.verify(logger).warn(messageCaptor.capture(), keyCaptor.capture(), issuesCaptor.capture());

        assertThat(messageCaptor.getValue()).contains("does not meet recommended password policy");
        assertThat(keyCaptor.getValue()).isEqualTo("security.keystore.password");

        final var renderedIssues = String.valueOf(issuesCaptor.getValue());
        assertThat(renderedIssues).contains("minLength>=12", "uppercase", "digit", "special");
        assertThat(renderedIssues).doesNotContain(password);
    }

    @Test
    void reportsOnlyMissingCharacterClassesWhenLengthIsSufficient() {
        final Logger logger = org.mockito.Mockito.mock(Logger.class);

        // Length >= 12, but missing uppercase and special
        KeystorePasswordPolicy.warnIfNonCompliant(logger, "k", "abcdefghijk1");

        final var issuesCaptor = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(logger)
                .warn(org.mockito.Mockito.anyString(), org.mockito.Mockito.any(), issuesCaptor.capture());

        assertThat(String.valueOf(issuesCaptor.getValue()))
                .contains("uppercase", "special")
                .doesNotContain("minLength>=12")
                .doesNotContain("lowercase")
                .doesNotContain("digit");
    }

    @Test
    void treatsNonLetterNonDigitCharactersAsSpecial() {
        final Logger logger = org.mockito.Mockito.mock(Logger.class);

        // Space counts as special, so should not warn about "special"
        KeystorePasswordPolicy.warnIfNonCompliant(logger, "k", "Abcdefghijk1 ");

        org.mockito.Mockito.verifyNoInteractions(logger);
    }

    @Test
    void nullArgumentsThrowWithHelpfulMessages() {
        final Logger logger = org.mockito.Mockito.mock(Logger.class);

        final var ex1 = org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class, () -> KeystorePasswordPolicy.warnIfNonCompliant(null, "k", "p"));
        assertThat(ex1.getMessage()).isEqualTo("logger must not be null");

        final var ex2 = org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class, () -> KeystorePasswordPolicy.warnIfNonCompliant(logger, null, "p"));
        assertThat(ex2.getMessage()).isEqualTo("configKey must not be null");

        final var ex3 = org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class, () -> KeystorePasswordPolicy.warnIfNonCompliant(logger, "k", null));
        assertThat(ex3.getMessage()).isEqualTo("password must not be null");
    }

    @Test
    void issuesOrderIsStableAndMatchesExpected() {
        final Logger logger = org.mockito.Mockito.mock(Logger.class);

        // Only uppercase present; length < 12
        KeystorePasswordPolicy.warnIfNonCompliant(logger, "k", "ABCDEFGHIJ");

        final var issuesCaptor = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(logger)
                .warn(org.mockito.Mockito.anyString(), org.mockito.Mockito.any(), issuesCaptor.capture());

        assertThat(String.valueOf(issuesCaptor.getValue()))
                .isEqualTo(String.join(", ", List.of("minLength>=12", "lowercase", "digit", "special")));
    }

    @Test
    void canBeCalledRepeatedlyWithoutSideEffects() {
        final Logger logger = org.mockito.Mockito.mock(Logger.class);

        for (int i = 0; i < 3; i++) {
            KeystorePasswordPolicy.warnIfNonCompliant(logger, "k", "short");
        }

        org.mockito.Mockito.verify(logger, org.mockito.Mockito.times(3))
                .warn(org.mockito.Mockito.anyString(), org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }
}

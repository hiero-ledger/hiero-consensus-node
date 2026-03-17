// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.common.test.fixtures.logging.MockAppender;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KeystorePasswordPolicyTest {

    private MockAppender appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        appender = new MockAppender("KeystorePasswordPolicyTest");
        logger = (Logger) LogManager.getLogger(KeystorePasswordPolicy.class);
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        logger.removeAppender(appender);
        appender.stop();
    }

    @Test
    void doesNothingWhenPasswordIsCompliant() {
        KeystorePasswordPolicy.warnIfNonCompliant("some.key", "Abcdefghijk1!");
        assertThat(appender.size()).isZero();
    }

    @Test
    void warnsWhenPasswordIsNonCompliantAndNeverLogsPasswordValue() {
        final var password = "short";
        KeystorePasswordPolicy.warnIfNonCompliant("security.keystore.password", password);

        assertThat(appender.size()).isEqualTo(1);
        final var renderedMessage = appender.get(0);
        assertThat(renderedMessage)
                .contains("does not meet recommended password policy")
                .contains("security.keystore.password")
                .contains("minLength>=12", "uppercase", "digit", "special")
                .doesNotContain(password);
    }

    @Test
    void reportsOnlyMissingCharacterClassesWhenLengthIsSufficient() {
        KeystorePasswordPolicy.warnIfNonCompliant("k", "abcdefghijk1");

        assertThat(appender.size()).isEqualTo(1);
        final var renderedMessage = appender.get(0);
        assertThat(renderedMessage)
                .contains("uppercase", "special")
                .doesNotContain("minLength>=12")
                .doesNotContain("lowercase")
                .doesNotContain("digit");
    }

    @Test
    void treatsNonLetterNonDigitCharactersAsSpecial() {
        KeystorePasswordPolicy.warnIfNonCompliant("k", "Abcdefghijk1 ");
        assertThat(appender.size()).isZero();
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

        assertThat(appender.size()).isEqualTo(1);
        final var renderedMessage = appender.get(0);
        assertThat(renderedMessage)
                .contains(String.join(", ", List.of("minLength>=12", "lowercase", "digit", "special")));
    }

    @Test
    void canBeCalledRepeatedlyWithoutSideEffects() {
        for (int i = 0; i < 3; i++) {
            KeystorePasswordPolicy.warnIfNonCompliant("k", "short");
        }

        assertThat(appender.size()).isEqualTo(3);
    }
}

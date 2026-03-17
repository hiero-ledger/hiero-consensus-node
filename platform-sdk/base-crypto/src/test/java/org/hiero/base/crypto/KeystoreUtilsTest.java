// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.swirlds.common.test.fixtures.logging.MockAppender;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KeystoreUtilsTest {

    private MockAppender appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        appender = new MockAppender("KeystoreUtilsTest");
        logger = (Logger) LogManager.getLogger(KeystoreUtils.class);
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
        KeystoreUtils.warnIfNonCompliant("Abcdefghijk1!");
        assertThat(appender.size()).isZero();
    }

    @Test
    void warnsWhenPasswordIsNonCompliantAndNeverLogsPasswordValue() {
        final var password = "short";
        KeystoreUtils.warnIfNonCompliant(password);

        assertThat(appender.size()).isEqualTo(1);
        final var renderedMessage = appender.get(0);
        assertThat(renderedMessage)
                .contains("does not meet recommended password policy")
                .contains("minLength>=12", "uppercase", "digit", "special")
                .doesNotContain(password);
    }

    @Test
    void reportsOnlyMissingCharacterClassesWhenLengthIsSufficient() {
        KeystoreUtils.warnIfNonCompliant("abcdefghijk1");

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
        KeystoreUtils.warnIfNonCompliant("Abcdefghijk1 ");
        assertThat(appender.size()).isZero();
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void nullArgumentsThrow() {
        assertThatThrownBy(() -> KeystoreUtils.getConfiguredPassword(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void issuesOrderIsStableAndMatchesExpected() {
        KeystoreUtils.warnIfNonCompliant("ABCDEFGHIJ");

        assertThat(appender.size()).isEqualTo(1);
        final var renderedMessage = appender.get(0);
        assertThat(renderedMessage)
                .contains(String.join(", ", List.of("minLength>=12", "lowercase", "digit", "special")));
    }

    @Test
    void canBeCalledRepeatedlyWithoutSideEffects() {
        for (int i = 0; i < 3; i++) {
            KeystoreUtils.warnIfNonCompliant("short");
        }

        assertThat(appender.size()).isEqualTo(3);
    }
}

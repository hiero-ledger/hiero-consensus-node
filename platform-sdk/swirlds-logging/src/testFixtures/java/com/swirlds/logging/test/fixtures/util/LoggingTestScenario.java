// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.test.fixtures.util;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.internal.LoggingSystem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Assertions;

/**
 * {@link LoggingTestScenario} is a relationship between a desired configuration and the list of assertions to verify
 * for that config
 */
public final class LoggingTestScenario {
    private final String scenarioName;
    private final TestConfigBuilder scenarioConfigBuilder;
    private final List<LoggingSystemAssertionRule> systemAssertionsRules;
    private final List<LoggerAssertionRule> loggerAssertionRules;
    private final Function<LoggingSystem, Logger> fromLoggingSystemToLogger;

    private LoggingTestScenario(
            String scenarioName,
            TestConfigBuilder builder,
            List<LoggingSystemAssertionRule> systemAssertionsRules,
            List<LoggerAssertionRule> loggerAssertionRules,
            Function<LoggingSystem, Logger> fromLoggingSystemToLogger) {
        this.scenarioName = scenarioName;
        this.scenarioConfigBuilder = builder;
        this.systemAssertionsRules = systemAssertionsRules;
        this.loggerAssertionRules = loggerAssertionRules;
        this.fromLoggingSystemToLogger = fromLoggingSystemToLogger;
    }

    public Configuration configuration() {
        return this.scenarioConfigBuilder.getOrCreateConfig();
    }

    public static LoggingTestScenarioBuilder builder() {
        return new LoggingTestScenarioBuilder();
    }

    public void verifyAssertionRules(LoggingSystem system) {
        try {
            for (LoggingSystemAssertionRule rule : this.systemAssertionsRules) {
                rule.performAssert(system);
            }

            if (!loggerAssertionRules.isEmpty()) {
                final Logger logger = fromLoggingSystemToLogger != null
                        ? fromLoggingSystemToLogger.apply(system)
                        : system.getLogger(this.scenarioName);
                for (LoggerAssertionRule rule : this.loggerAssertionRules) {
                    rule.performAssert(logger);
                }
            }
        } catch (AssertionError e) {
            throw new AssertionError("Failure in scenario: \"" + scenarioName + "\" " + e.getMessage(), e);
        }
    }

    public void verifyAssertionRules() {
        final LoggingSystem system = new LoggingSystem(configuration());
        system.installHandlers();
        system.installProviders();
        verifyAssertionRules(system);
    }

    public void verifyAssertionRules(Function<Configuration, LoggingSystem> builder) {
        verifyAssertionRules(builder.apply(configuration()));
    }

    public static class LoggingTestScenarioBuilder {
        private String name;
        private final Map<String, Object> properties = new HashMap<>();
        private TestConfigBuilder configurationBuilder;
        private Function<LoggingSystem, Logger> getLoggerFunction = null;
        private final List<LoggingSystemAssertionRule> systemAssertionsRules = new ArrayList<>();
        private final List<LoggerAssertionRule> loggerAssertionsRules = new ArrayList<>();

        private LoggingTestScenarioBuilder() {}

        public LoggingTestScenarioBuilder name(String name) {
            this.name = name;
            return this;
        }

        public LoggingTestScenarioBuilder withConfiguration(TestConfigBuilder configuration) {
            this.configurationBuilder = configuration;
            return this;
        }

        public LoggingTestScenarioBuilder withConfiguration(String key, Object value) {
            this.properties.put(key, value);
            return this;
        }

        public LoggingTestScenarioBuilder assertThatLevelIsAllowed(String loggerName, Level level) {
            systemAssertionsRules.add(new LoggingSystemAssertionRuleImpl(loggerName, level, true));
            return this;
        }

        public LoggingTestScenarioBuilder assertThatLevelIsNotAllowed(String loggerName, Level level) {
            systemAssertionsRules.add(new LoggingSystemAssertionRuleImpl(loggerName, level, false));
            return this;
        }

        public LoggingTestScenarioBuilder assertThatLevelIsAllowed(Level level) {
            loggerAssertionsRules.add(new LoggerAssertionRuleImpl(level, true));
            return this;
        }

        public LoggingTestScenarioBuilder assertThatLevelIsNotAllowed(Level level) {
            loggerAssertionsRules.add(new LoggerAssertionRuleImpl(level, false));
            return this;
        }

        public LoggingTestScenarioBuilder assertWitLogger(Consumer<Logger> assertions) {
            loggerAssertionsRules.add(assertions::accept);
            return this;
        }

        public LoggingTestScenarioBuilder assertWitLoggingSystem(Consumer<LoggingSystem> assertions) {
            systemAssertionsRules.add(assertions::accept);
            return this;
        }

        public LoggingTestScenarioBuilder withGetLogger(Function<LoggingSystem, Logger> getLoggerFunction) {
            this.getLoggerFunction = getLoggerFunction;
            return this;
        }

        public LoggingTestScenario build() {
            TestConfigBuilder testConfigBuilder =
                    this.configurationBuilder == null ? new TestConfigBuilder() : configurationBuilder;
            properties.forEach(testConfigBuilder::withValue);
            return new LoggingTestScenario(
                    this.name, testConfigBuilder, systemAssertionsRules, loggerAssertionsRules, getLoggerFunction);
        }
    }

    private interface LoggerAssertionRule {
        void performAssert(final Logger logger);
    }

    private interface LoggingSystemAssertionRule {
        void performAssert(final LoggingSystem logger);
    }

    private record LoggingSystemAssertionRuleImpl(String loggerName, Level level, Boolean expectedResult)
            implements LoggingSystemAssertionRule {
        public void performAssert(final LoggingSystem system) {
            if (this.expectedResult() != null) {
                Assertions.assertEquals(
                        this.expectedResult(),
                        system.isEnabled(this.loggerName(), this.level(), null),
                        String.format("AssertionRule %s: but actual was:%s%n", this, !this.expectedResult()));
            }
        }
    }

    private record LoggerAssertionRuleImpl(Level level, Boolean expectedResult) implements LoggerAssertionRule {
        public void performAssert(final Logger logger) {
            if (this.expectedResult() != null) {
                Assertions.assertEquals(
                        this.expectedResult(),
                        logger.isEnabled(this.level()),
                        String.format("AssertionRule %s: but actual was:%s%n", this, !this.expectedResult()));
            }
        }
    }

    public String getScenarioName() {
        return scenarioName;
    }
}

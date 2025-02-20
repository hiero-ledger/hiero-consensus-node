// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

import static com.hedera.services.bdd.suites.SuiteRunner.SUITE_NAME_WIDTH;
import static com.hedera.services.bdd.suites.SuiteRunner.rightPadded;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.suites.HapiSuite;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;

/**
 * Base class with some utility methods that can be used by JUnit-based test classes.
 */
public abstract class TestBase {
    public static List<HapiSpec> extractContextualizedSpecsFrom(
            final List<Supplier<HapiSuite>> suiteSuppliers,
            final Function<HapiSuite, Stream<HapiSpec>> internalSpecsExtractor) {
        return extractContextualizedSpecsFrom(suiteSuppliers, internalSpecsExtractor, s -> {});
    }

    public static List<HapiSpec> extractContextualizedSpecsFrom(
            final List<Supplier<HapiSuite>> suiteSuppliers,
            final Function<HapiSuite, Stream<HapiSpec>> internalSpecsExtractor,
            final Consumer<String> nameConsumer) {
        return suiteSuppliers.stream()
                .map(Supplier::get)
                .peek(suite -> nameConsumer.accept(suite.name()))
                .flatMap(internalSpecsExtractor)
                .toList();
    }

    public static void concurrentExecutionOf(final List<HapiSpec> specs) {
        HapiSuite.runConcurrentSpecs(specs);
        final var failures = specs.stream().filter(HapiSpec::notOk).toList();
        if (!failures.isEmpty()) {
            final var failureReport =
                    """
                            %d specs FAILED. By suite,
                            %s
                            """;
            final var details = new StringBuilder();
            // The stream below is a bit tricky. It is grouping the specs by suite name, and then
            // for each suite, it is collecting the specs that failed, and then for each failed
            // spec,
            // it is collecting the details of the failure. The details are the exception message
            // and
            // the toString() of the failed operation.
            failures.stream()
                    .collect(Collectors.groupingBy(HapiSpec::getSuitePrefix, Collectors.toList()))
                    .forEach((suiteName, failedSpecs) -> {
                        details.append("  ")
                                .append(rightPadded(suiteName, SUITE_NAME_WIDTH))
                                .append(" -> ")
                                .append(failedSpecs.size())
                                .append(" failures:\n");
                        failedSpecs.forEach(failure -> details.append("    ")
                                .append(failure.getName())
                                .append(": ")
                                .append(failure.getCause())
                                .append("\n"));
                    });
            Assertions.fail(String.format(failureReport, failures.size(), details));
        }
    }

    public static Stream<HapiSpec> contextualizedSpecsFromConcurrent(final HapiSuite suite) {
        return suffixContextualizedSpecsFromConcurrent(suite, "");
    }

    private static Stream<HapiSpec> suffixContextualizedSpecsFromConcurrent(
            final HapiSuite suite, final String suffix) {
        if (!suite.canRunConcurrent()) {
            throw new IllegalArgumentException(suite.name() + " specs cannot run concurrently");
        }
        // Re-use gRPC channels for all specs
        suite.skipClientTearDown();
        // Don't log unnecessary detail
        suite.setOnlyLogHeader();
        return suite.getSpecsInSuiteWithOverrides().stream().map(spec -> spec.setSuitePrefix(suite.name() + suffix));
    }
}

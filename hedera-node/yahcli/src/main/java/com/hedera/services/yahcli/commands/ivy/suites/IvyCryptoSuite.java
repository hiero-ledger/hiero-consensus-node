// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy.suites;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * Implements the old {@code ValidationScenarios} crypto scenario.
 */
public class IvyCryptoSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(IvyCryptoSuite.class);

    private final Map<String, String> specConfig;
    private final ScenariosConfig scenariosConfig;

    public IvyCryptoSuite(@NonNull final Map<String, String> specConfig, @NonNull final ScenariosConfig scenariosConfig, Supplier<String> nodeAccountSupplier) {
        this.specConfig = requireNonNull(specConfig);
        this.scenariosConfig = requireNonNull(scenariosConfig);
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(cryptoScenario());
    }

    final Stream<DynamicTest> cryptoScenario() {
        return HapiSpec.customHapiSpec("CryptoScenario")
                .withProperties(specConfig)
                .given()
                .when()
                .then();
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}

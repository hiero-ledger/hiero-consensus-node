// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy.suites;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.PrivateKey;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

/**
 * Implements the old {@code ValidationScenarios} crypto scenario.
 */
public class IvyCryptoSuite extends AbstractIvySuite {
    private static final Logger log = LogManager.getLogger(IvyCryptoSuite.class);

    public IvyCryptoSuite(
            @NonNull Map<String, String> specConfig,
            @NonNull ScenariosConfig scenariosConfig,
            @NonNull Supplier<String> nodeAccountSupplier,
            @NonNull Runnable persistUpdatedScenarios,
            @NonNull LongFunction<PrivateKey> accountKeyLoader) {
        super(specConfig, scenariosConfig, nodeAccountSupplier, persistUpdatedScenarios, accountKeyLoader);
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(cryptoScenario());
    }

    final Stream<DynamicTest> cryptoScenario() {
        return HapiSpec.customHapiSpec("CryptoScenario")
                .withProperties(specConfig)
                .given(ensureScenarioPayer())
                .when()
                .then();
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}

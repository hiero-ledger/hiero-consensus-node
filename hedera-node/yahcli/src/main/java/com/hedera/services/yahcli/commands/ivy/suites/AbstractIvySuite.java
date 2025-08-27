// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy.suites;

import static com.hedera.services.bdd.spec.utilops.UtilVerbs.fundOrCreateEd25519Account;
import static com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig.SCENARIO_PAYER_NAME;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.PrivateKey;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.function.Supplier;

public abstract class AbstractIvySuite extends HapiSuite {
    protected final Map<String, String> specConfig;
    protected final ScenariosConfig scenariosConfig;
    protected final Supplier<String> nodeAccountSupplier;
    protected final Runnable persistUpdatedScenarios;
    protected final LongFunction<PrivateKey> accountKeyLoader;

    protected AbstractIvySuite(
            @NonNull final Map<String, String> specConfig,
            @NonNull final ScenariosConfig scenariosConfig,
            @NonNull final Supplier<String> nodeAccountSupplier,
            @NonNull final Runnable persistUpdatedScenarios,
            @NonNull final LongFunction<PrivateKey> accountKeyLoader) {
        this.specConfig = requireNonNull(specConfig);
        this.scenariosConfig = requireNonNull(scenariosConfig);
        this.nodeAccountSupplier = requireNonNull(nodeAccountSupplier);
        this.persistUpdatedScenarios = requireNonNull(persistUpdatedScenarios);
        this.accountKeyLoader = requireNonNull(accountKeyLoader);
    }

    protected SpecOperation ensureScenarioPayer() {
        return fundOrCreateEd25519Account(
                SCENARIO_PAYER_NAME,
                scenariosConfig.getScenarioPayer(),
                scenariosConfig.getEnsureScenarioPayerHbars() * TINY_PARTS_PER_WHOLE,
                accountKeyLoader,
                (number, key) -> {
                    scenariosConfig.setScenarioPayer(number);
                    persistUpdatedScenarios.run();
                });
    }
}

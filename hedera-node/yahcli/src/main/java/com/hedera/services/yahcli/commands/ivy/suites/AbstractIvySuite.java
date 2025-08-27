// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy.suites;

import static com.hedera.services.bdd.spec.utilops.UtilVerbs.fundOrCreateEd25519Account;
import static com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig.SCENARIO_PAYER_NAME;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.commands.ivy.scenarios.Scenarios;
import com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig;
import com.hedera.services.yahcli.config.YahcliKeys;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;

public abstract class AbstractIvySuite extends HapiSuite {
    protected final Map<String, String> specConfig;
    protected final ScenariosConfig scenariosConfig;
    protected final Supplier<Supplier<String>> nodeAccounts;
    protected final Runnable persistUpdatedScenarios;
    protected final YahcliKeys yahcliKeys;

    protected AbstractIvySuite(
            @NonNull final Map<String, String> specConfig,
            @NonNull final ScenariosConfig scenariosConfig,
            @NonNull final Supplier<Supplier<String>> nodeAccounts,
            @NonNull final Runnable persistUpdatedScenarios,
            @NonNull final YahcliKeys yahcliKeys) {
        this.specConfig = requireNonNull(specConfig);
        this.scenariosConfig = requireNonNull(scenariosConfig);
        this.nodeAccounts = requireNonNull(nodeAccounts);
        this.persistUpdatedScenarios = requireNonNull(persistUpdatedScenarios);
        this.yahcliKeys = yahcliKeys;
    }

    protected SpecOperation ensureScenarioPayer() {
        return ensureEd25519Account(
                SCENARIO_PAYER_NAME,
                scenariosConfig.getScenarioPayer(),
                scenariosConfig.getEnsureScenarioPayerHbars() * TINY_PARTS_PER_WHOLE,
                scenariosConfig::setScenarioPayer);
    }

    protected SpecOperation ensureEd25519Account(
            @NonNull final String name,
            @Nullable final Long maybeNumber,
            final long desiredBalance,
            @NonNull LongConsumer onCreatedNumber) {
        return fundOrCreateEd25519Account(
                name,
                maybeNumber,
                desiredBalance,
                number -> yahcliKeys.loadAccountKey(number, EdDSAPrivateKey.class),
                spec -> {
                    final long createdNum = spec.registry().getAccountID(name).getAccountNum();
                    onCreatedNumber.accept(createdNum);
                    yahcliKeys.exportAccountKey(spec, name);
                    persistUpdatedScenarios.run();
                });
    }

    protected Scenarios getOrCreateScenarios() {
        if (scenariosConfig.getScenarios() == null) {
            scenariosConfig.setScenarios(new Scenarios());
        }
        return scenariosConfig.getScenarios();
    }
}

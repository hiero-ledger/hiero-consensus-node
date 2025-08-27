// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.bdd;

import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.TEST_NETWORK;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.yahcli.commands.ivy.scenarios.Scenarios;
import com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Consumer;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

/**
 * Exposes or removes the yahcli scenarios config as requested.
 */
public class YahcliScenariosConfigOperation extends AbstractYahcliOperation<YahcliScenariosConfigOperation> {
    private final boolean removeExisting;

    @Nullable
    private final Consumer<Scenarios> scenariosCb;

    @Nullable
    private final Consumer<ScenariosConfig> configCb;

    public YahcliScenariosConfigOperation(
            final boolean removeExisting,
            @Nullable final Consumer<Scenarios> scenariosCb,
            @Nullable final Consumer<ScenariosConfig> configCb) {
        this.removeExisting = removeExisting;
        this.scenariosCb = scenariosCb;
        this.configCb = configCb;
    }

    @Override
    protected YahcliScenariosConfigOperation self() {
        return this;
    }

    @Override
    public Optional<Throwable> execFor(@NonNull final HapiSpec spec) {
        requireNonNull(spec);
        try {
            final var configYmlPath = Paths.get(workingDirOrThrow(), TEST_NETWORK, "scenarios", "config.yml");
            if (removeExisting) {
                Files.deleteIfExists(configYmlPath);
            } else {
                final var yamlIn = new Yaml(new Constructor(ScenariosConfig.class, new LoaderOptions()));
                final ScenariosConfig config = yamlIn.load(Files.newInputStream(configYmlPath));
                if (configCb != null) {
                    configCb.accept(config);
                }
                if (scenariosCb != null) {
                    scenariosCb.accept(config.getScenarios());
                }
            }
        } catch (Throwable t) {
            return Optional.of(t);
        }
        return Optional.empty();
    }
}

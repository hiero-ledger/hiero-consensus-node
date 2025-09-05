// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.bdd;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.yahcli.config.domain.GlobalConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Consumer;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

/**
 * Exposes the yahcli global config as requested.
 */
public class YahcliConfigOperation extends AbstractYahcliOperation<YahcliConfigOperation> {
    private final Consumer<GlobalConfig> configCb;

    public YahcliConfigOperation(@NonNull final Consumer<GlobalConfig> configCb) {
        this.configCb = requireNonNull(configCb);
    }

    @Override
    protected YahcliConfigOperation self() {
        return this;
    }

    @Override
    public Optional<Throwable> execFor(@NonNull final HapiSpec spec) {
        requireNonNull(spec);
        try {
            final var configYmlPath = Paths.get(workingDirOrThrow(), "config.yml");
            final var yamlIn = new Yaml(new Constructor(GlobalConfig.class, new LoaderOptions()));
            final GlobalConfig config = yamlIn.load(Files.newInputStream(configYmlPath));
            configCb.accept(config);
        } catch (Throwable t) {
            return Optional.of(t);
        }
        return Optional.empty();
    }
}

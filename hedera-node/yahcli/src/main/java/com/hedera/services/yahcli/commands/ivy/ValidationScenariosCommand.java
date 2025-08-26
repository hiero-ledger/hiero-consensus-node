// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.guaranteedExtantDir;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.yahcli.commands.ivy.ValidationScenariosCommand.Scenario.CONSENSUS;
import static com.hedera.services.yahcli.commands.ivy.ValidationScenariosCommand.Scenario.CONTRACT;
import static com.hedera.services.yahcli.commands.ivy.ValidationScenariosCommand.Scenario.CRYPTO;
import static com.hedera.services.yahcli.commands.ivy.ValidationScenariosCommand.Scenario.FILE;
import static com.hedera.services.yahcli.commands.ivy.ValidationScenariosCommand.Scenario.XFERS;
import static java.util.Collections.disjoint;
import static java.util.stream.Collectors.toMap;
import static org.hiero.base.concurrent.interrupt.Uninterruptable.abortIfInterrupted;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig;
import com.hedera.services.yahcli.commands.ivy.suites.IvyCryptoSuite;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.config.ConfigUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.nodes.Tag;
import picocli.CommandLine;

@CommandLine.Command(
        name = "vs",
        subcommands = {CommandLine.HelpCommand.class},
        description = "Runs legacy validation scenarios")
public class ValidationScenariosCommand implements Callable<Integer> {
    private static final Set<Scenario> SCENARIOS_REQUIRING_PAYER = EnumSet.of(CRYPTO, FILE, CONTRACT, CONSENSUS, XFERS);

    @CommandLine.ParentCommand
    IvyCommand ivyCommand;

    @CommandLine.Option(
            names = {"--crypto"},
            description = "Include the legacy crypto scenario")
    boolean crypto;

    @CommandLine.Option(
            names = {"--file"},
            description = "Include the legacy file scenario")
    boolean file;

    @CommandLine.Option(
            names = {"--contract"},
            description = "Include the legacy contract scenario")
    boolean contract;

    @CommandLine.Option(
            names = {"--consensus"},
            description = "Include the legacy consensus scenario")
    boolean consensus;

    @CommandLine.Option(
            names = {"--xfers"},
            description = "Include the legacy xfers scenario")
    boolean xfers;

    @CommandLine.Option(
            names = {"--staking"},
            description = "Include the legacy staking scenario")
    boolean staking;

    @Override
    public Integer call() throws Exception {
        final var yahcli = ivyCommand.getYahcli();
        final var config = ConfigUtils.configFrom(yahcli);
        final var scenariosLoc = config.scenariosDir();
        final var configYml = guaranteedExtantDir(Paths.get(scenariosLoc)).resolve("config.yml");
        final var scenariosConfig = loadScenariosConfig(configYml, config);
        final var specConfig = config.asSpecConfig();

        final Set<Scenario> scenarios = EnumSet.copyOf(Stream.<Stream<Scenario>>of(
                        crypto ? Stream.of(CRYPTO) : Stream.empty(),
                        file ? Stream.of(FILE) : Stream.empty(),
                        contract ? Stream.of(CONTRACT) : Stream.empty(),
                        consensus ? Stream.of(CONTRACT) : Stream.empty(),
                        xfers ? Stream.of(XFERS) : Stream.empty(),
                        staking ? Stream.of(Scenario.STAKING) : Stream.empty())
                .flatMap(Function.identity())
                .toList());
        final var nodeAccountSupplier = nodeAccountSupplier(scenariosConfig, config);
        if (!disjoint(SCENARIOS_REQUIRING_PAYER, scenarios)) {}

        final var results = scenarios.stream()
                .collect(toMap(
                        Function.identity(),
                        scenario -> run(scenario, specConfig, scenariosConfig, nodeAccountSupplier)));

        return 0;
    }

    private HapiSpec.SpecStatus run(
            @NonNull final Scenario scenario,
            @NonNull final Map<String, String> specConfig,
            @NonNull final ScenariosConfig scenariosConfig,
            @NonNull final Supplier<String> nodeAccountSupplier) {
        final HapiSuite delegate =
                switch (scenario) {
                    case CRYPTO -> new IvyCryptoSuite(specConfig, scenariosConfig, nodeAccountSupplier);
                    case FILE -> {
                        throw new AssertionError("Not implemented");
                    }
                    case CONTRACT -> {
                        throw new AssertionError("Not implemented");
                    }
                    case CONSENSUS -> {
                        throw new AssertionError("Not implemented");
                    }
                    case XFERS -> {
                        throw new AssertionError("Not implemented");
                    }
                    case STAKING -> {
                        throw new AssertionError("Not implemented");
                    }
                };
        delegate.runSuiteSync();
        return delegate.getFinalSpecs().getFirst().getStatus();
    }
    ;

    enum Scenario {
        CRYPTO,
        FILE,
        CONTRACT,
        CONSENSUS,
        XFERS,
        STAKING,
    }

    private static Supplier<String> nodeAccountSupplier(
            @NonNull final ScenariosConfig scenariosConfig, @NonNull final ConfigManager config) {
        final List<String> nodeAccountIds = config.asNodeInfos().stream()
                .map(info -> asAccountString(info.getAccount()))
                .toList();
        final AtomicInteger nextAccountId = new AtomicInteger(0);
        final int n = nodeAccountIds.size();
        return () -> {
            final var accountId = nodeAccountIds.get(nextAccountId.getAndUpdate(i -> (i + 1) % n));
            abortIfInterrupted(() -> Thread.sleep(scenariosConfig.getSleepMsBeforeNextNode()));
            return accountId;
        };
    }

    private static ScenariosConfig loadScenariosConfig(@NonNull final Path p, @NonNull final ConfigManager config) {
        final var yamlIn = new Yaml(new Constructor(ScenariosConfig.class, new LoaderOptions()));
        final var f = p.toFile();
        if (!f.exists()) {
            try (final var fout = Files.newBufferedWriter(p)) {
                final var yamlOut = new Yaml();
                final var defaultConfig = new ScenariosConfig();
                defaultConfig.setBootstrap(config.defaultPayerNumOrThrow());
                final var doc = yamlOut.dumpAs(defaultConfig, Tag.MAP, null);
                fout.write(doc);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to initialize default config.yml at " + p.toAbsolutePath(), e);
            }
        }
        try {
            return yamlIn.load(Files.newInputStream(p));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load scenarios config from " + p.toAbsolutePath(), e);
        }
    }
}

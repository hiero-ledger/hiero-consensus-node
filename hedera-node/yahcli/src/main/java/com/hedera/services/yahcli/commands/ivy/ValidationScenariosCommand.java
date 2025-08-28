// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.guaranteedExtantDir;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiSpec.SpecStatus.PASSED;
import static com.hedera.services.yahcli.commands.ivy.ValidationScenariosCommand.Scenario.CONTRACT;
import static com.hedera.services.yahcli.commands.ivy.ValidationScenariosCommand.Scenario.CRYPTO;
import static com.hedera.services.yahcli.commands.ivy.ValidationScenariosCommand.Scenario.FILE;
import static com.hedera.services.yahcli.commands.ivy.ValidationScenariosCommand.Scenario.XFERS;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;
import static org.hiero.base.concurrent.interrupt.Uninterruptable.abortIfInterrupted;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig;
import com.hedera.services.yahcli.commands.ivy.suites.IvyCryptoSuite;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.config.YahcliKeys;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
        description = "Runs original validation scenarios")
public class ValidationScenariosCommand implements Callable<Integer> {
    enum Scenario {
        CRYPTO,
        FILE,
        CONTRACT,
        CONSENSUS,
        XFERS,
        STAKING,
    }

    @CommandLine.ParentCommand
    IvyCommand ivyCommand;

    @CommandLine.Option(
            names = {"--crypto"},
            description = "Include the VS crypto scenario")
    boolean crypto;

    @CommandLine.Option(
            names = {"--file"},
            description = "Include the VS file scenario")
    boolean file;

    @CommandLine.Option(
            names = {"--contract"},
            description = "Include the VS contract scenario")
    boolean contract;

    @CommandLine.Option(
            names = {"--consensus"},
            description = "Include the VS consensus scenario")
    boolean consensus;

    @CommandLine.Option(
            names = {"--xfers"},
            description = "Include the VS xfers scenario")
    boolean xfers;

    @CommandLine.Option(
            names = {"--staking"},
            description = "Include the VS staking scenario")
    boolean staking;

    @CommandLine.Option(
            names = {"-n", "--new-entities"},
            description = "Enable the VS 'novel' flag")
    boolean novel;

    @Nullable
    private Path configYmlPath;

    @Nullable
    private ScenariosConfig scenariosConfig;

    @Override
    public Integer call() throws Exception {
        final var yahcli = ivyCommand.getYahcli();
        final var config = ConfigUtils.configFrom(yahcli);
        final var scenariosLoc = config.scenariosDirOrThrow();
        configYmlPath = guaranteedExtantDir(Paths.get(scenariosLoc)).resolve("config.yml");
        loadScenariosConfig(config);
        requireNonNull(scenariosConfig);
        final var specConfig = config.asSpecConfig();
        final var scenariosToRun = requestedScenarios();
        final var nodeAccounts = nodeAccounts(scenariosConfig, config);
        final Runnable persistUpdatedScenarios = this::persistCurrentScenariosConfig;
        final var yahcliKeys = config.keys();
        final var results = scenariosToRun.stream()
                .collect(toMap(
                        Function.identity(),
                        scenario -> run(scenario, specConfig, nodeAccounts, persistUpdatedScenarios, yahcliKeys)));
        return results.values().stream().allMatch(PASSED::equals) ? 0 : 1;
    }

    private HapiSpec.SpecStatus run(
            @NonNull final Scenario scenario,
            @NonNull final Map<String, String> specConfig,
            @NonNull final Supplier<Supplier<String>> nodeAccounts,
            @NonNull final Runnable persistUpdatedScenarios,
            @NonNull final YahcliKeys yahcliKeys) {
        requireNonNull(scenariosConfig);
        final HapiSuite delegate =
                switch (scenario) {
                    case CRYPTO ->
                        new IvyCryptoSuite(
                                specConfig, scenariosConfig, nodeAccounts, persistUpdatedScenarios, yahcliKeys, novel);
                    case FILE -> throw new AssertionError("Not implemented");
                    case CONTRACT -> throw new AssertionError("Not implemented");
                    case CONSENSUS -> throw new AssertionError("Not implemented");
                    case XFERS -> throw new AssertionError("Not implemented");
                    case STAKING -> throw new AssertionError("Not implemented");
                };
        delegate.runSuiteSync();
        return delegate.getFinalSpecs().getFirst().getStatus();
    }

    private Set<Scenario> requestedScenarios() {
        return EnumSet.copyOf(Stream.<Stream<Scenario>>of(
                        crypto ? Stream.of(CRYPTO) : Stream.empty(),
                        file ? Stream.of(FILE) : Stream.empty(),
                        contract ? Stream.of(CONTRACT) : Stream.empty(),
                        consensus ? Stream.of(CONTRACT) : Stream.empty(),
                        xfers ? Stream.of(XFERS) : Stream.empty(),
                        staking ? Stream.of(Scenario.STAKING) : Stream.empty())
                .flatMap(Function.identity())
                .toList());
    }

    private static Supplier<Supplier<String>> nodeAccounts(
            @NonNull final ScenariosConfig scenariosConfig, @NonNull final ConfigManager config) {
        final List<String> nodeAccountIds = config.asNodeInfos().stream()
                .map(info -> asAccountString(info.getAccount()))
                .toList();
        final AtomicInteger nextAccountId = new AtomicInteger(0);
        final int n = nodeAccountIds.size();
        return () -> {
            final var accountId = nodeAccountIds.get(nextAccountId.getAndUpdate(i -> (i + 1) % n));
            final var slept = new AtomicBoolean();
            return () -> {
                if (!slept.compareAndSet(false, true)) {
                    abortIfInterrupted(() -> Thread.sleep(scenariosConfig.getSleepMsBeforeNextNode()));
                }
                return accountId;
            };
        };
    }

    private void loadScenariosConfig(@NonNull final ConfigManager config) {
        final var f = requireNonNull(configYmlPath).toFile();
        if (!f.exists()) {
            scenariosConfig = new ScenariosConfig();
            scenariosConfig.setBootstrap(config.defaultPayerNumOrThrow());
            persistCurrentScenariosConfig();
        }
        final var yamlIn = new Yaml(new Constructor(ScenariosConfig.class, new LoaderOptions()));
        try {
            scenariosConfig = yamlIn.load(Files.newInputStream(configYmlPath));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load scenarios config from " + configYmlPath.toAbsolutePath(), e);
        }
    }

    private void persistCurrentScenariosConfig() {
        requireNonNull(configYmlPath);
        requireNonNull(scenariosConfig);
        try (final var fout = Files.newBufferedWriter(configYmlPath)) {
            final var yamlOut = new Yaml();
            final var doc = yamlOut.dumpAs(scenariosConfig, Tag.MAP, null);
            fout.write(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to initialize default config.yml at " + configYmlPath.toAbsolutePath(), e);
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.guaranteedExtantDir;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.rm;
import static com.hedera.services.bdd.junit.support.TestPlanUtils.hasAnnotatedTestNode;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.SharedNetworkLauncherSessionListener;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNode;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.Yahcli;
import com.hedera.services.yahcli.config.domain.GlobalConfig;
import com.hedera.services.yahcli.config.domain.NetConfig;
import com.hedera.services.yahcli.config.domain.NodeConfig;
import com.hedera.services.yahcli.test.bdd.YahcliVerbs;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * A {@link LauncherSessionListener} that, when the test plan includes subprocess {@link HapiTest}s,
 * configures {@link Yahcli} to target the {@link SubProcessNetwork} spun up for the tests by writing
 * a corresponding {@code config.yml} to the yahcli working directory.
 */
public class YahcliLauncherSessionListener implements LauncherSessionListener {
    private static final String BUILD_DIR = "build";
    private static final String SCOPE = "yahcli";
    private static final String KEYS_DIR = "keys";
    private static final String CONFIG_YML = "config.yml";
    private static final Path BASE_WORKING_DIR = Path.of(BUILD_DIR, SCOPE);

    @Override
    public void launcherSessionOpened(@NonNull final LauncherSession session) {
        session.getLauncher().registerTestExecutionListeners(new YahcliExecutionListener());
    }

    public static class YahcliExecutionListener implements TestExecutionListener {
        @Override
        public void testPlanExecutionStarted(@NonNull final TestPlan testPlan) {
            // Only do extra setup if it's possible we're targeting a SubprocessNetwork
            if (!hasAnnotatedTestNode(testPlan, Set.of(HapiTest.class, LeakyHapiTest.class))) {
                return;
            }
            SharedNetworkLauncherSessionListener.onSubProcessNetworkReady(
                    YahcliLauncherSessionListener::writeYahcliConfigYml);
            HapiSuite.DEFAULT_TEARDOWN = false;
        }
    }

    private static void writeYahcliConfigYml(@NonNull final HederaNetwork network) {
        if (!(network instanceof SubProcessNetwork subProcessNetwork)) {
            throw new IllegalStateException("Expected a SubProcessNetwork, got a " + network.getClass());
        }
        rm(BASE_WORKING_DIR);
        final var keysDir = guaranteedExtantDir(
                BASE_WORKING_DIR.resolve(YahcliVerbs.TEST_NETWORK).resolve(KEYS_DIR));
        try (final var in = Thread.currentThread().getContextClassLoader().getResourceAsStream("genesis.pem")) {
            requireNonNull(in);
            Files.copy(in, keysDir.resolve("account2.pem"));
            Files.writeString(keysDir.resolve("account2.pass"), "swirlds");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write yahcli config", e);
        }
        final var netConfig = new NetConfig();
        netConfig.setShard(subProcessNetwork.shard());
        netConfig.setRealm(subProcessNetwork.realm());
        netConfig.setDefaultPayer("2");
        final var nodesConfig = subProcessNetwork.nodes().stream()
                .map(SubProcessNode.class::cast)
                .map(node -> {
                    final var nodeConfig = new NodeConfig();
                    nodeConfig.setId((int) node.getNodeId());
                    nodeConfig.setShard(subProcessNetwork.shard());
                    nodeConfig.setRealm(subProcessNetwork.realm());
                    nodeConfig.setAccount(node.metadata().accountId().accountNumOrThrow());
                    nodeConfig.setIpv4Addr(
                            node.metadata().host() + ":" + node.metadata().grpcPort());
                    return nodeConfig;
                })
                .toList();
        netConfig.setNodes(nodesConfig);
        netConfig.setDefaultNodeAccount((int) nodesConfig.getFirst().getAccount());
        final var config = new GlobalConfig();
        config.setNetworks(Map.of(YahcliVerbs.TEST_NETWORK, netConfig));
        config.setDefaultNetwork(YahcliVerbs.TEST_NETWORK);

        final var yamlOut = new Yaml();
        final var doc = yamlOut.dumpAs(config, Tag.MAP, null);
        final var configPath = BASE_WORKING_DIR.resolve(CONFIG_YML);
        try (final var writer = Files.newBufferedWriter(configPath)) {
            writer.write(doc);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write yahcli config to " + configPath.toAbsolutePath(), e);
        }
        YahcliVerbs.setDefaultConfigLoc(configPath.toAbsolutePath().toString());
        YahcliVerbs.setDefaultWorkingDir(BUILD_DIR + File.separator + SCOPE);
    }
}

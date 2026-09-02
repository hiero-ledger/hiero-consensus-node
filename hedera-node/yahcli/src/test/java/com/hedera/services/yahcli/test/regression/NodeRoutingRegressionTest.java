// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.regression;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.guaranteedExtantDir;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.rm;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doAdhoc;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.DEFAULT_WORKING_DIR;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.TEST_NETWORK;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.newAccountCapturer;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliAccounts;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.yaml.snakeyaml.nodes.Tag.MAP;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.props.NodeConnectInfo;
import com.hedera.services.yahcli.config.domain.GlobalConfig;
import com.hedera.services.yahcli.config.domain.NetConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

/**
 * Regression for the bug where {@link NetConfig#toSpecProperties()} emitted {@code #nodeId} suffixes
 * in the {@code nodes} property, causing {@link NodeConnectInfo} to fall back to sequential account
 * numbering instead of parsing the real account literals.
 *
 * <p>On mainnet, retired nodes create gaps in the DAB nodeId sequence (e.g. nodeIds 2 and 3 are
 * retired), so real accounts are non-sequential (3, 4, 7, 8, 9, …). Sequential fallback numbering
 * (3, 4, 5, 6, 7, …) diverges at the first gap, routing gRPC calls to the wrong node, which then
 * rejects transactions with {@code INVALID_NODE_ACCOUNT}.
 *
 * <p>Unit-level assertions for {@code toSpecProperties()} live in
 * {@code com.hedera.services.yahcli.test.config.NetConfigTest}.
 */
@Tag(REGRESSION)
public class NodeRoutingRegressionTest {

    /**
     * Verifies that account-creation requests succeed when routed explicitly to both nodes in a
     * gap topology. Uses nodes 0 and 3 from the test 4-node network (nodeIds 0 and 3,
     * accounts 3 and 6, skipping nodeIds 1 and 2).
     *
     * <p>Without the fix, {@code toSpecProperties()} includes {@code #id} suffixes, causing
     * {@link NodeConnectInfo} to assign sequential stub accounts 3 and 4. Routing to node account 6
     * then finds no matching stub and throws, making yahcli exit non-zero.
     *
     * <p>With the fix, stubs are keyed by accounts 3 and 6 correctly, so both account-creation
     * requests succeed and the resulting accounts carry the expected 1-hbar balance.
     */
    @HapiTest
    final Stream<DynamicTest> accountCreationSucceedsWhenRoutedToGapNodes() {
        final var gapConfigPath = new AtomicReference<String>();
        final var gapWorkDirPath = new AtomicReference<String>();
        final var node0Account = new AtomicReference<String>();
        final var node3Account = new AtomicReference<String>();
        final var acctViaNode0 = new AtomicLong();
        final var acctViaNode3 = new AtomicLong();

        return hapiTest(
                doAdhoc(() -> {
                    try {
                        setupGapWorkDir(gapConfigPath, gapWorkDirPath, node0Account, node3Account);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }),
                // Create an account routed explicitly to node 0 (account 3 in gap topology)
                doAdhoc(() -> yahcliAccounts("create", "-d", "hbar", "-a", "1")
                        .withConfigLoc(gapConfigPath.get())
                        .withWorkingDir(gapWorkDirPath.get())
                        .withNodeAccount(node0Account.get())
                        .exposingOutputTo(newAccountCapturer(acctViaNode0::set))),
                // Create an account routed explicitly to node 3 (account 6 in gap topology — the gap node)
                doAdhoc(() -> yahcliAccounts("create", "-d", "hbar", "-a", "1")
                        .withConfigLoc(gapConfigPath.get())
                        .withWorkingDir(gapWorkDirPath.get())
                        .withNodeAccount(node3Account.get())
                        .exposingOutputTo(newAccountCapturer(acctViaNode3::set))),
                // Verify both accounts were created with the expected 1-hbar balance
                sourcingContextual(spec -> getAccountInfo(
                                asAccountString(spec.accountIdFactory().apply(acctViaNode0.get())))
                        .has(accountWith().balance(ONE_HBAR))),
                sourcingContextual(spec -> getAccountInfo(
                                asAccountString(spec.accountIdFactory().apply(acctViaNode3.get())))
                        .has(accountWith().balance(ONE_HBAR))));
    }

    /**
     * Builds a gap-topology config from the network (nodes 0 and 3 only, skipping 1 and 2),
     * writes it to a fresh working directory, copies the genesis key material, and populates the
     * node account string references for use in routing subsequent commands.
     */
    private static void setupGapWorkDir(
            AtomicReference<String> gapConfigPath,
            AtomicReference<String> gapWorkDirPath,
            AtomicReference<String> node0Account,
            AtomicReference<String> node3Account)
            throws IOException {
        final var defaultWorkDir = Path.of(DEFAULT_WORKING_DIR.get());

        // Read the network's config to get actual host:port info
        final var currentConfigFile = defaultWorkDir.resolve("config.yml");
        final var yamlIn = new Yaml(new Constructor(GlobalConfig.class, new LoaderOptions()));
        final GlobalConfig global;
        try (final var in = Files.newInputStream(currentConfigFile)) {
            global = yamlIn.load(in);
        }
        final var network = global.getNetworks().get(TEST_NETWORK);
        final var allNodes = network.getNodes();
        assertTrue(allNodes.size() >= 4, "Expected at least 4 nodes to form a gap topology");

        // Take nodes at indices 0 and 3: their nodeIds skip 1 and 2, so accounts are non-sequential
        final var gapNodes = List.of(allNodes.get(0), allNodes.get(3));
        node0Account.set(String.valueOf(gapNodes.get(0).getAccount()));
        node3Account.set(String.valueOf(gapNodes.get(1).getAccount()));

        final var gapNet = new NetConfig();
        gapNet.setShard(network.getShard());
        gapNet.setRealm(network.getRealm());
        gapNet.setDefaultPayer(network.getDefaultPayer());
        gapNet.setNodes(gapNodes);
        gapNet.setDefaultNodeAccount((int) gapNodes.getFirst().getAccount());

        final var gapGlobal = new GlobalConfig();
        gapGlobal.setNetworks(Map.of(TEST_NETWORK, gapNet));
        gapGlobal.setDefaultNetwork(TEST_NETWORK);

        // Set up a fresh working directory for the gap scenario
        final var gapDir = Path.of("build", "yahcli-gap-routing-test").toAbsolutePath();
        rm(gapDir);
        final var keysDir = guaranteedExtantDir(gapDir.resolve(TEST_NETWORK).resolve("keys"));

        // Copy genesis key material from the default working directory
        final var srcKeys = defaultWorkDir.resolve(TEST_NETWORK).resolve("keys");
        Files.copy(srcKeys.resolve("account2.pem"), keysDir.resolve("account2.pem"));
        Files.copy(srcKeys.resolve("account2.pass"), keysDir.resolve("account2.pass"));

        // Write the gap config.yml
        final var yamlOut = new Yaml();
        final var doc = yamlOut.dumpAs(gapGlobal, MAP, null);
        final var configFile = gapDir.resolve("config.yml");
        Files.writeString(configFile, doc);

        gapConfigPath.set(configFile.toString());
        gapWorkDirPath.set(gapDir.toString());
    }
}

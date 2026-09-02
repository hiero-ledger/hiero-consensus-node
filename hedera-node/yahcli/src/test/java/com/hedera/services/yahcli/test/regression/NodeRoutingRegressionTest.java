// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.regression;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.guaranteedExtantDir;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.rm;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.DEFAULT_WORKING_DIR;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.TEST_NETWORK;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliIvy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.props.NodeConnectInfo;
import com.hedera.services.yahcli.config.domain.GlobalConfig;
import com.hedera.services.yahcli.config.domain.NetConfig;
import com.hedera.services.yahcli.config.domain.NodeConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 */
@Tag(REGRESSION)
public class NodeRoutingRegressionTest {

    /**
     * Verifies that a crypto transfer actually succeeds when the yahcli config uses nodes with
     * non-sequential nodeIds (a gap topology). Uses nodes 0 and 3 from the embedded 4-node
     * network (nodeIds 0 and 3, accounts 3 and 6, skipping nodeIds 1 and 2).
     *
     * <p>Without the fix, {@code toSpecProperties()} includes {@code #id} suffixes, causing
     * {@link NodeConnectInfo} to fall back to sequential accounts 3 and 4. When {@code nodeAccounts}
     * (built from {@code toNodeInfos()}, which correctly strips suffixes) cycles to account 6,
     * {@code HapiClients.stubId()} finds no stub for account 6 and throws, causing yahcli to exit
     * non-zero and the test to fail.
     *
     * <p>With the fix, stubs are keyed by accounts 3 and 6 correctly, so routing to account 6
     * succeeds and the crypto transfer completes.
     */
    @HapiTest
    final Stream<DynamicTest> cryptoTransferSucceedsWhenNodeIdsHaveGaps() {
        final var gapConfigPath = new AtomicReference<String>();
        final var gapWorkDirPath = new AtomicReference<String>();

        return hapiTest(
                doingContextual(spec -> {
                    try {
                        setupGapWorkDir(gapConfigPath, gapWorkDirPath);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }),
                sourcingContextual(spec -> yahcliIvy("scenarios", "--crypto")
                        .withConfigLoc(gapConfigPath.get())
                        .withWorkingDir(gapWorkDirPath.get())));
    }

    /**
     * Builds a gap-topology config from the embedded network (nodes 0 and 3 only, skipping 1 and 2),
     * writes it to a fresh working directory, and copies the genesis key material.
     */
    private static void setupGapWorkDir(
            AtomicReference<String> gapConfigPath, AtomicReference<String> gapWorkDirPath) throws IOException {
        final var defaultWorkDir = Path.of(DEFAULT_WORKING_DIR.get());

        // Read the embedded network's config to get actual host:port info
        final var embeddedConfigFile = defaultWorkDir.resolve("config.yml");
        final var yamlIn = new Yaml(new Constructor(GlobalConfig.class, new LoaderOptions()));
        final GlobalConfig embeddedGlobal;
        try (final var in = Files.newInputStream(embeddedConfigFile)) {
            embeddedGlobal = yamlIn.load(in);
        }
        final var embeddedNet = embeddedGlobal.getNetworks().get(TEST_NETWORK);
        final var allNodes = embeddedNet.getNodes();
        assertTrue(allNodes.size() >= 4, "Expected at least 4 embedded nodes to form a gap topology");

        // Take nodes at indices 0 and 3: their nodeIds skip 1 and 2, so accounts are non-sequential
        final var gapNodes = List.of(allNodes.get(0), allNodes.get(3));

        final var gapNet = new NetConfig();
        gapNet.setShard(embeddedNet.getShard());
        gapNet.setRealm(embeddedNet.getRealm());
        gapNet.setDefaultPayer(embeddedNet.getDefaultPayer());
        gapNet.setNodes(gapNodes);
        gapNet.setDefaultNodeAccount((int) gapNodes.get(0).getAccount());

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
        final var doc = yamlOut.dumpAs(gapGlobal, org.yaml.snakeyaml.nodes.Tag.MAP, null);
        final var configFile = gapDir.resolve("config.yml");
        Files.writeString(configFile, doc);

        gapConfigPath.set(configFile.toString());
        gapWorkDirPath.set(gapDir.toString());
    }

    private static NodeConfig nodeConfig(int id, long account, String ip) {
        final var n = new NodeConfig();
        n.setId(id);
        n.setAccount(account);
        n.setIpv4Addr(ip);
        return n;
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

import static com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension.REPEATABLE_KEY_GENERATOR;
import static com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension.SHARED_BLOCK_NODE_NETWORK;
import static com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension.SHARED_NETWORK;
import static com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork.SHARED_NETWORK_NAME;
import static com.hedera.services.bdd.spec.HapiPropertySource.getConfigRealm;
import static com.hedera.services.bdd.spec.HapiPropertySource.getConfigShard;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.junit.hedera.BlockNodeNetwork;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode;
import com.hedera.services.bdd.junit.hedera.embedded.EmbeddedNetwork;
import com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.infrastructure.HapiClients;
import com.hedera.services.bdd.spec.keys.RepeatableKeyGenerator;
import com.hedera.services.bdd.spec.remote.RemoteNetworkFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Registers a {@link TestExecutionListener} when the {@link LauncherSession} is opened to
 * start the shared test network before the test plan is executed; and stop it after test
 * plan execution finishes.
 */
public class SharedNetworkLauncherSessionListener implements LauncherSessionListener {
    private static final Logger log = LogManager.getLogger(SharedNetworkLauncherSessionListener.class);
    public static final int CLASSIC_HAPI_TEST_NETWORK_SIZE = 4;

    @Override
    public void launcherSessionOpened(@NonNull final LauncherSession session) {
        session.getLauncher().registerTestExecutionListeners(new SharedNetworkExecutionListener());
    }

    /**
     * A {@link TestExecutionListener} that starts the shared network before the test plan is executed,
     * unless the requested mode is a per-class network, in which case the network is started per class.
     */
    public static class SharedNetworkExecutionListener implements TestExecutionListener {
        private enum Embedding {
            NA,
            PER_CLASS,
            CONCURRENT,
            REPEATABLE
        }

        private Embedding embedding;

        @Override
        public void testPlanExecutionStarted(@NonNull final TestPlan testPlan) {
            // Check if any test in the plan uses HapiBlockNode
            if (planUsesHapiBlockNode(testPlan)) {
                log.info("Test plan includes HapiBlockNode annotation, skipping shared network startup.");
                // Ensure repeatable key generator is still set up even if network isn't
                REPEATABLE_KEY_GENERATOR.set(new RepeatableKeyGenerator());
                embedding = Embedding.NA; // Set embedding state appropriately
                return; // Skip network setup
            }

            REPEATABLE_KEY_GENERATOR.set(new RepeatableKeyGenerator());
            embedding = embeddingMode();
            final HederaNetwork network =
                    switch (embedding) {
                        // Embedding is not applicable for a subprocess network
                        case NA -> {
                            final boolean isRemote = Optional.ofNullable(System.getProperty("hapi.spec.remote"))
                                    .map(Boolean::parseBoolean)
                                    .orElse(false);
                            yield isRemote ? sharedRemoteNetworkIfRequested() : sharedSubProcessNetwork(null, null);
                        }
                        // For the default Test task, we need to run some tests in concurrent embedded mode and
                        // some in repeatable embedded mode, depending on the value of their @TargetEmbeddedMode
                        // annotation; this PER_CLASS value supports that requirement
                        case PER_CLASS -> null;
                        case CONCURRENT -> EmbeddedNetwork.newSharedNetwork(EmbeddedMode.CONCURRENT);
                        case REPEATABLE -> EmbeddedNetwork.newSharedNetwork(EmbeddedMode.REPEATABLE);
                    };
            if (network != null) {
                checkPrOverridesForBlockNodeStreaming(network);
                network.start();
                SHARED_NETWORK.set(network);
            }
        }

        @Override
        public void testPlanExecutionFinished(@NonNull final TestPlan testPlan) {
            if (embedding == Embedding.NA) {
                HapiClients.tearDown();
            }
            Optional.ofNullable(SHARED_NETWORK.get()).ifPresent(HederaNetwork::terminate);
        }

        /**
         * Restarts the shared embedded network with the given mode.
         * @param mode the mode in which to restart the shared embedded network
         */
        public static void ensureEmbedding(@NonNull final EmbeddedMode mode) {
            requireNonNull(mode);
            if (SHARED_NETWORK.get() != null) {
                if (SHARED_NETWORK.get() instanceof EmbeddedNetwork embeddedNetwork) {
                    if (embeddedNetwork.mode() != mode) {
                        SHARED_NETWORK.get().terminate();
                        SHARED_NETWORK.set(null);
                    }
                } else {
                    throw new IllegalStateException("Shared network is not an embedded network");
                }
            }
            if (SHARED_NETWORK.get() == null) {
                startSharedEmbedded(mode);
            }
        }

        private @Nullable HederaNetwork sharedRemoteNetworkIfRequested() {
            final var sharedTargetYml = System.getProperty("hapi.spec.nodes.remoteYml");
            return (sharedTargetYml != null) ? RemoteNetworkFactory.newWithTargetFrom(sharedTargetYml) : null;
        }

        /**
         * Creates a shared subprocess network.
         * @param networkName the name of the network
         * @return the shared subprocess network
         */
        public static HederaNetwork sharedSubProcessNetwork(String networkName, Integer specifiedNetworkSize) {
            final int networkSize = specifiedNetworkSize != null
                    ? specifiedNetworkSize
                    : Optional.ofNullable(System.getProperty("hapi.spec.network.size"))
                            .map(Integer::parseInt)
                            .orElse(CLASSIC_HAPI_TEST_NETWORK_SIZE);
            final var initialPortProperty = System.getProperty("hapi.spec.initial.port");
            if (!initialPortProperty.isBlank()) {
                final var initialPort = Integer.parseInt(initialPortProperty);
                SubProcessNetwork.initializeNextPortsForNetwork(networkSize, initialPort);
            }

            final var prepareUpgradeOffsetsProperty = System.getProperty("hapi.spec.prepareUpgradeOffsets");
            if (prepareUpgradeOffsetsProperty != null) {
                final List<Duration> offsets = Arrays.stream(prepareUpgradeOffsetsProperty.split(","))
                        .map(Duration::parse)
                        .sorted()
                        .distinct()
                        .toList();
                if (!offsets.isEmpty()) {
                    HapiSpec.doDelayedPrepareUpgrades(offsets);
                }
            }

            return SubProcessNetwork.newSharedNetwork(
                    networkName != null ? networkName : SHARED_NETWORK_NAME,
                    networkSize,
                    getConfigShard(),
                    getConfigRealm());
        }

        private static void startSharedEmbedded(@NonNull final EmbeddedMode mode) {
            SHARED_NETWORK.set(EmbeddedNetwork.newSharedNetwork(mode));
            SHARED_NETWORK.get().start();
        }

        private static Embedding embeddingMode() {
            final var mode = Optional.ofNullable(System.getProperty("hapi.spec.embedded.mode"))
                    .orElse("");
            return switch (mode) {
                case "per-class" -> Embedding.PER_CLASS;
                case "concurrent" -> Embedding.CONCURRENT;
                case "repeatable" -> Embedding.REPEATABLE;
                default -> Embedding.NA;
            };
        }

        /**
         * Recursively checks if any test identifier in the test plan corresponds to a method
         * annotated with {@link HapiBlockNode}.
         *
         * @param testPlan The test plan.
         * @return {@code true} if any test method is annotated, {@code false} otherwise.
         */
        private boolean planUsesHapiBlockNode(@NonNull final TestPlan testPlan) {
            final Set<TestIdentifier> roots = testPlan.getRoots();
            log.info("Checking test plan roots for HapiBlockNode: {}", roots);
            for (final TestIdentifier root : roots) {
                if (testIdentifierOrDescendantsUseHapiBlockNode(testPlan, root)) {
                    log.info("Found HapiBlockNode annotation in root or descendants: {}", root);
                    return true;
                }
            }
            log.info("No HapiBlockNode annotation found in test plan.");
            return false;
        }

        /**
         * Checks if a given TestIdentifier or any of its descendants corresponds to a test method
         * annotated with {@link HapiBlockNode}.
         *
         * @param testPlan The test plan (needed to get children).
         * @param identifier The current TestIdentifier to check.
         * @return {@code true} if the identifier or a descendant uses the annotation, {@code false} otherwise.
         */
        private boolean testIdentifierOrDescendantsUseHapiBlockNode(
                @NonNull final TestPlan testPlan, @NonNull final TestIdentifier identifier) {
            log.trace("Checking identifier for HapiBlockNode: {}", identifier.getUniqueId());
            // Check for MethodSource first, as @TestFactory methods might not have type TEST
            final Optional<TestSource> source = identifier.getSource();
            if (source.isPresent() && source.get() instanceof MethodSource methodSource) {
                log.trace("Identifier has MethodSource: {}", identifier.getUniqueId());
                try {
                    final var method = methodSource.getJavaMethod();
                    final var annotations = Arrays.toString(method.getDeclaredAnnotations());
                    log.trace("Method source found: {}, annotations: {}", method.getName(), annotations);
                    if (method.isAnnotationPresent(HapiBlockNode.class)) {
                        log.info("HapiBlockNode annotation FOUND on method: {}", method.getName());
                        return true; // Annotation found
                    } else {
                        log.trace("HapiBlockNode annotation NOT found on method: {}", method.getName());
                    }
                } catch (Exception e) {
                    log.warn("Could not get Java method or annotations for source: {}", source, e);
                }
            } else if (identifier.getType() == TestDescriptor.Type.TEST) {
                // Log if it's a TEST but doesn't have MethodSource (less common)
                log.trace("Identifier is a TEST but lacks MethodSource: {}", identifier.getUniqueId());
            } else {
                log.trace("Identifier is not a TEST and has no MethodSource: {}", identifier.getUniqueId());
            }

            // Recursively check children
            final Set<TestIdentifier> children = testPlan.getChildren(identifier);
            log.trace("Checking {} children of {}", children.size(), identifier.getUniqueId());
            for (final TestIdentifier child : children) {
                if (testIdentifierOrDescendantsUseHapiBlockNode(testPlan, child)) {
                    log.trace(
                            "Found HapiBlockNode in child: {}, returning true for parent: {}",
                            child.getUniqueId(),
                            identifier.getUniqueId());
                    return true; // Found in descendant
                }
            }

            log.trace(
                    "HapiBlockNode not found for identifier or its descendants: {}, returning false",
                    identifier.getUniqueId());
            return false; // Not found in this branch
        }
    }

    private static void checkPrOverridesForBlockNodeStreaming(HederaNetwork network) {
        if (network instanceof SubProcessNetwork) {
            Map<String, String> prCheckOverrides = ProcessUtils.prCheckOverrides();
            if (prCheckOverrides.containsKey("blockStream.writerMode")
                    && prCheckOverrides.get("blockStream.writerMode").equals("FILE_AND_GRPC")) {
                log.info(
                        "PR Check Override: blockStream.writerMode=FILE_AND_GRPC is set, configuring a Block Node network");
                BlockNodeNetwork blockNodeNetwork = new BlockNodeNetwork();
                network.nodes().forEach(node -> {
                    blockNodeNetwork.getBlockNodeModeById().put(node.getNodeId(), BlockNodeMode.SIMULATOR);
                    blockNodeNetwork
                            .getBlockNodeIdsBySubProcessNodeId()
                            .put(node.getNodeId(), new long[] {node.getNodeId()});
                    blockNodeNetwork.getBlockNodePrioritiesBySubProcessNodeId().put(node.getNodeId(), new long[] {0});
                });
                blockNodeNetwork.start();
                SHARED_BLOCK_NODE_NETWORK.set(blockNodeNetwork);
                SubProcessNetwork subProcessNetwork = (SubProcessNetwork) network;
                subProcessNetwork
                        .getPostInitWorkingDirActions()
                        .add(blockNodeNetwork::configureBlockNodeConnectionInformation);
            }
        }
    }
}

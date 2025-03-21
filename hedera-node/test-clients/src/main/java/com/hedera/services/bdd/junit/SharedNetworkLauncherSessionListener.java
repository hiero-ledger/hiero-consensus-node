// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

import static com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension.REPEATABLE_KEY_GENERATOR;
import static com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension.SHARED_NETWORK;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode;
import com.hedera.services.bdd.junit.hedera.embedded.EmbeddedNetwork;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.infrastructure.HapiClients;
import com.hedera.services.bdd.spec.keys.RepeatableKeyGenerator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

/**
 * Registers a {@link TestExecutionListener} when the {@link LauncherSession} is opened to
 * start the shared test network before the test plan is executed; and stop it after test
 * plan execution finishes.
 */
public class SharedNetworkLauncherSessionListener implements LauncherSessionListener {
    public static final int CLASSIC_HAPI_TEST_NETWORK_SIZE = 4;
    private static final Logger log = LogManager.getLogger(SharedNetworkLauncherSessionListener.class);

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
            REPEATABLE_KEY_GENERATOR.set(new RepeatableKeyGenerator());
            embedding = embeddingMode();
            final HederaNetwork network =
                    switch (embedding) {
                            // Embedding is not applicable for a subprocess network
                        case NA -> {
                            final int networkSize = Optional.ofNullable(System.getProperty("hapi.spec.network.size"))
                                    .map(Integer::parseInt)
                                    .orElse(CLASSIC_HAPI_TEST_NETWORK_SIZE);
                            final var initialPortProperty = System.getProperty("hapi.spec.initial.port");
                            if (!initialPortProperty.isBlank()) {
                                final var initialPort = Integer.parseInt(initialPortProperty);
                                SubProcessNetwork.initializeNextPortsForNetwork(networkSize, initialPort);
                            }
                            final var prepareUpgradeOffsetsProperty =
                                    System.getProperty("hapi.spec.prepareUpgradeOffsets");
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
                            SubProcessNetwork subProcessNetwork =
                                    (SubProcessNetwork) SubProcessNetwork.newSharedNetwork(networkSize);

                            // Check for the blocknode mode system property
                            String blockNodeModeProperty = System.getProperty("hapi.spec.blocknode.mode");
                            if (blockNodeModeProperty != null && !blockNodeModeProperty.isEmpty()) {
                                switch (blockNodeModeProperty.toUpperCase()) {
                                    case "SIM":
                                        subProcessNetwork.setBlockNodeMode(BlockNodeMode.SIMULATOR);
                                        break;
                                    case "REAL":
                                        subProcessNetwork.setBlockNodeMode(BlockNodeMode.REAL);
                                        break;
                                    case "LOCAL":
                                        subProcessNetwork.setBlockNodeMode(BlockNodeMode.LOCAL_NODE);
                                        break;
                                    default:
                                        log.warn(
                                                "Invalid hapi.spec.blocknode.mode value: {}. Using NONE.",
                                                blockNodeModeProperty);
                                        subProcessNetwork.setBlockNodeMode(BlockNodeMode.NONE);
                                }
                            } else {
                                // Default to NONE if not specified
                                subProcessNetwork.setBlockNodeMode(BlockNodeMode.NONE);
                            }

                            String blockNodeManyToOneProperty =
                                    System.getProperty("hapi.spec.blocknode.simulator.manyToOne");
                            if (blockNodeManyToOneProperty != null && !blockNodeManyToOneProperty.isEmpty()) {
                                subProcessNetwork.setManyToOneSimulator(
                                        Boolean.parseBoolean(blockNodeManyToOneProperty));
                            }

                            yield subProcessNetwork;
                        }
                            // For the default Test task, we need to run some tests in concurrent embedded mode and
                            // some in repeatable embedded mode, depending on the value of their @TargetEmbeddedMode
                            // annotation; this PER_CLASS value supports that requirement
                        case PER_CLASS -> null;
                        case CONCURRENT -> EmbeddedNetwork.newSharedNetwork(EmbeddedMode.CONCURRENT);
                        case REPEATABLE -> EmbeddedNetwork.newSharedNetwork(EmbeddedMode.REPEATABLE);
                    };
            if (network != null) {
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
    }
}

/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.services.bdd.junit;

import static com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension.REPEATABLE_KEY_GENERATOR;
import static com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension.SHARED_NETWORK;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode;
import com.hedera.services.bdd.junit.hedera.embedded.EmbeddedNetwork;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.infrastructure.HapiClients;
import com.hedera.services.bdd.spec.keys.RepeatableKeyGenerator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import java.util.Set;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
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
            REPEATABLE_KEY_GENERATOR.set(new RepeatableKeyGenerator());
            embedding = embeddingMode();
            final HederaNetwork network =
                    switch (embedding) {
                            // Embedding is not applicable for a subprocess network
                        case NA -> {
                            final var initialPortProperty = System.getProperty("hapi.spec.initial.port");
                            if (!initialPortProperty.isBlank()) {
                                final var initialPort = Integer.parseInt(initialPortProperty);
                                SubProcessNetwork.initializeNextPortsForNetwork(
                                        CLASSIC_HAPI_TEST_NETWORK_SIZE, initialPort);
                            }

                            final boolean isIssScenario = isIssScenario(testPlan);
                            yield SubProcessNetwork.newSharedNetwork(CLASSIC_HAPI_TEST_NETWORK_SIZE, isIssScenario);
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

        private static boolean isIssScenario(final TestPlan testPlan) {
            final Set<TestIdentifier> testChildren =
                    testPlan.getChildren(testPlan.getRoots().iterator().next());
            if (testChildren.iterator().hasNext()) {
                final Optional<TestSource> testSource =
                        testChildren.iterator().next().getSource();
                if (testSource.isPresent() && testSource.get() instanceof ClassSource tscs) {
                    final Class<?> javaClass = tscs.getJavaClass();
                    return javaClass.isAnnotationPresent(IssHapiTest.class);
                }
            }

            return false;
        }
    }
}

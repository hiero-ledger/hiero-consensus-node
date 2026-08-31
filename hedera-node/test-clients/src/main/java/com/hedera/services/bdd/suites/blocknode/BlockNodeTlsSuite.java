// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.BlockNodeVerbs.blockNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertBlockNodeCommsLogDoesNotContainText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.awaitBlockNodeCommsLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlocks;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.HapiBlockNode.BlockNodeConfig;
import com.hedera.services.bdd.HapiBlockNode.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.junit.hedera.BlockNodeTlsMode;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * Verifies that a consensus node can publish its block stream to a block node whose APIs are secured with TLS.
 *
 * <p>Each block node API is configured independently in {@code block-nodes.json}, so TLS may be required on the
 * publish API alone, on every API, or on neither. Simulators present a self-signed certificate that the consensus node
 * trusts by pinning its SHA-384 fingerprint, which is how an operator would configure a block node fronted by a
 * TLS-terminating proxy.
 *
 * <p>NOTE: com.hedera.node.app.blocks.impl.streaming MUST have DEBUG logging enabled.
 */
@Tag(BLOCK_NODE)
@OrderedInIsolation
public class BlockNodeTlsSuite {
    private static final Duration LOG_WAIT = Duration.ofSeconds(60);

    /**
     * Acceptance criterion 1: the publish API requires TLS while the service API (server status) stays plaintext. The
     * two APIs are on different ports, so the consensus node must secure each connection independently.
     */
    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR, tls = BlockNodeTlsMode.PUBLISH_ONLY)
            },
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockNode.globalCoolDownSeconds", "1",
                            "blockNode.basicNodeCoolDownSeconds", "1",
                            "blockNode.extendedNodeCoolDownSeconds", "1"
                        })
            })
    @Order(1)
    final Stream<DynamicTest> publishOverTlsWithPlaintextStatusApi() {
        return streamsSuccessfullyToBlockNodeZero();
    }

    /**
     * Acceptance criterion 2: every block node API requires TLS.
     */
    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR, tls = BlockNodeTlsMode.ALL)
            },
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockNode.globalCoolDownSeconds", "1",
                            "blockNode.basicNodeCoolDownSeconds", "1",
                            "blockNode.extendedNodeCoolDownSeconds", "1"
                        })
            })
    @Order(2)
    final Stream<DynamicTest> publishOverTlsWithAllApisSecured() {
        return streamsSuccessfullyToBlockNodeZero();
    }

    /**
     * Acceptance criterion 3: TLS is enabled for one block node without being enabled for the others. The consensus
     * node streams to the TLS-secured node first, and falls back to the plaintext node when it goes away.
     */
    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR, tls = BlockNodeTlsMode.ALL),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.SIMULATOR, tls = BlockNodeTlsMode.NONE)
            },
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0, 1},
                        blockNodePriorities = {0, 1},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockNode.globalCoolDownSeconds", "1",
                            "blockNode.basicNodeCoolDownSeconds", "1",
                            "blockNode.extendedNodeCoolDownSeconds", "1"
                        })
            })
    @Order(3)
    final Stream<DynamicTest> tlsEnabledForOneBlockNodeOnly() {
        final AtomicInteger securedPort = new AtomicInteger();
        final AtomicInteger plaintextPort = new AtomicInteger();
        final AtomicReference<Set<Long>> securedBlocks = new AtomicReference<>();
        final AtomicReference<Set<Long>> plaintextBlocks = new AtomicReference<>();
        return hapiTest(
                doingContextual(spec -> {
                    securedPort.set(spec.getBlockNodePortById(0));
                    plaintextPort.set(spec.getBlockNodePortById(1));
                }),
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                sourcingContextual(spec -> awaitBlockNodeCommsLogContainsText(
                        byNodeId(0), activeConnectionText(securedPort.get()), LOG_WAIT)),
                blockNode(0).getReceivedBlockNumbersExposing(securedBlocks::set),
                doingContextual(spec -> assertThat(securedBlocks.get())
                        .as("the TLS-secured block node should have received blocks")
                        .isNotEmpty()),

                // Drop the TLS node; the consensus node must fail over to the plaintext node
                blockNode(0).shutDownImmediately(),
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                sourcingContextual(spec -> awaitBlockNodeCommsLogContainsText(
                        byNodeId(0), activeConnectionText(plaintextPort.get()), LOG_WAIT)),
                blockNode(1).getReceivedBlockNumbersExposing(plaintextBlocks::set),
                doingContextual(spec -> assertThat(plaintextBlocks.get())
                        .as("the plaintext block node should have received blocks after failover")
                        .isNotEmpty()));
    }

    /**
     * Negative case: the consensus node is configured with a certificate fingerprint that does not match the one the
     * block node presents. The TLS handshake must fail, and the consensus node must fall back to a block node it can
     * verify rather than streaming to an unverified peer.
     */
    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {
                @BlockNodeConfig(
                        nodeId = 0,
                        mode = BlockNodeMode.SIMULATOR,
                        tls = BlockNodeTlsMode.ALL_BAD_FINGERPRINT),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.SIMULATOR, tls = BlockNodeTlsMode.NONE)
            },
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0, 1},
                        blockNodePriorities = {0, 1},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockNode.globalCoolDownSeconds", "1",
                            "blockNode.basicNodeCoolDownSeconds", "1",
                            "blockNode.extendedNodeCoolDownSeconds", "1"
                        })
            })
    @Order(4)
    final Stream<DynamicTest> mismatchedCertificateFingerprintIsRejected() {
        final AtomicInteger untrustedPort = new AtomicInteger();
        final AtomicInteger trustedPort = new AtomicInteger();
        final AtomicReference<Set<Long>> untrustedBlocks = new AtomicReference<>();
        final AtomicReference<Set<Long>> trustedBlocks = new AtomicReference<>();
        return hapiTest(
                doingContextual(spec -> {
                    untrustedPort.set(spec.getBlockNodePortById(0));
                    trustedPort.set(spec.getBlockNodePortById(1));
                }),
                waitUntilNextBlocks(5).withBackgroundTraffic(true),

                // The higher-priority node cannot be verified, so the consensus node uses the lower-priority one
                sourcingContextual(spec -> awaitBlockNodeCommsLogContainsText(
                        byNodeId(0), activeConnectionText(trustedPort.get()), LOG_WAIT)),
                sourcingContextual(spec -> assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), activeConnectionText(untrustedPort.get()), Duration.ZERO)),
                blockNode(0).getReceivedBlockNumbersExposing(untrustedBlocks::set),
                blockNode(1).getReceivedBlockNumbersExposing(trustedBlocks::set),
                doingContextual(spec -> {
                    assertThat(untrustedBlocks.get())
                            .as("no blocks should reach a block node whose certificate could not be verified")
                            .isEmpty();
                    assertThat(trustedBlocks.get())
                            .as("blocks should reach the block node the consensus node can verify")
                            .isNotEmpty();
                }));
    }

    /**
     * Asserts that the consensus node establishes an active streaming connection to block node 0 and that blocks
     * actually arrive there, with no connection errors along the way.
     */
    private Stream<DynamicTest> streamsSuccessfullyToBlockNodeZero() {
        final AtomicInteger streamingPort = new AtomicInteger();
        final AtomicReference<Set<Long>> received = new AtomicReference<>();
        return hapiTest(
                doingContextual(spec -> streamingPort.set(spec.getBlockNodePortById(0))),
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                sourcingContextual(spec -> awaitBlockNodeCommsLogContainsText(
                        byNodeId(0), activeConnectionText(streamingPort.get()), LOG_WAIT)),
                awaitBlockNodeCommsLogContainsText(
                        byNodeId(0), "Sending request to block node (type: END_OF_BLOCK)", LOG_WAIT),
                blockNode(0).getReceivedBlockNumbersExposing(received::set),
                doingContextual(spec -> assertThat(received.get())
                        .as("the TLS-secured block node should have received blocks")
                        .isNotEmpty()),
                assertBlockNodeCommsLogDoesNotContainText(byNodeId(0), "Error received", Duration.ZERO),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Exception caught in connection worker thread", Duration.ZERO),
                assertBlockNodeCommsLogDoesNotContainText(
                        byNodeId(0), "Failed to read block node configuration from", Duration.ZERO));
    }

    private static String activeConnectionText(final int port) {
        return String.format("/localhost:%s/ACTIVE] Connection state transitioned from READY to ACTIVE", port);
    }
}

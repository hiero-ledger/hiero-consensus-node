// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import static com.hedera.services.bdd.junit.hedera.ExternalPath.APPLICATION_LOG;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.internal.network.Network;
import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.MarkerFile;
import com.hedera.services.bdd.junit.hedera.NodeMetadata;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.junit.hedera.SystemFunctionalityTarget;
import com.hedera.services.bdd.junit.hedera.subprocess.NodeStatus;
import com.hedera.services.bdd.junit.hedera.subprocess.PrometheusClient;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.TargetNetworkType;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionResponse;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.hiero.consensus.model.status.PlatformStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UntilLogContainsOpTest {

    @Test
    @DisplayName("Captures regex groups once all selected nodes agree on the same match")
    void capturesMatchGroupsAcrossNodes(@TempDir final Path tempDir) throws Exception {
        final var firstLog = tempDir.resolve("node1.log");
        final var secondLog = tempDir.resolve("node2.log");
        Files.writeString(firstLog, "round=42\n");
        Files.writeString(secondLog, "round=42\n");

        final var capture = new AtomicReference<String>();
        final var condition = new LogContainmentCondition(APPLICATION_LOG, null, Pattern.compile("round=(\\d+)"))
                .exposingMatchGroupTo(1, capture);

        assertTrue(condition.isSatisfiedBy(List.of(new TestNode("node1", firstLog), new TestNode("node2", secondLog))));
        assertEquals("42", capture.get());
    }

    @Test
    @DisplayName("Runs fresh operation batches until the target log text appears")
    void runsUntilTargetTextAppears(@TempDir final Path tempDir) throws Exception {
        final var applicationLog = tempDir.resolve("application.log");
        Files.writeString(applicationLog, "");

        final var spec = specTargeting(new TestNetwork(new TestNode("node0", applicationLog)));
        final var attempts = new AtomicInteger(0);
        final var op = new UntilLogContainsOp(
                        NodeSelector.allNodes(), APPLICATION_LOG, "target line", null, ignore -> new SpecOperation[] {
                            new AppendLineOp(
                                    applicationLog, attempts.incrementAndGet() >= 3 ? "target line\n" : "noise line\n")
                        })
                .lasting(Duration.ofSeconds(1))
                .pollingEvery(Duration.ZERO)
                .loggingOff();

        assertTrue(op.execFor(spec).isEmpty());
        assertEquals(3, attempts.get());
        assertTrue(Files.readString(applicationLog).contains("target line"));
    }

    private static HapiSpec specTargeting(final HederaNetwork network) throws Exception {
        final var spec = new HapiSpec("UntilLogContainsOpTest", new SpecOperation[0]);
        final Field field = HapiSpec.class.getDeclaredField("targetNetwork");
        field.setAccessible(true);
        field.set(spec, network);
        return spec;
    }

    private static final class AppendLineOp extends UtilOp {
        private final Path logPath;
        private final String line;

        private AppendLineOp(final Path logPath, final String line) {
            this.logPath = logPath;
            this.line = line;
        }

        @Override
        protected boolean submitOp(final HapiSpec spec) throws Throwable {
            Files.writeString(logPath, line, CREATE, APPEND);
            return false;
        }
    }

    private record TestNetwork(HederaNode node) implements HederaNetwork {
        @Override
        public HapiPropertySource startupProperties() {
            return HapiSpecSetup.getDefaultPropertySource();
        }

        @Override
        public Response send(
                final Query query,
                final HederaFunctionality functionality,
                final com.hederahashgraph.api.proto.java.AccountID nodeAccountId,
                final boolean asNodeOperator) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TransactionResponse submit(
                final Transaction transaction,
                final HederaFunctionality functionality,
                final SystemFunctionalityTarget target,
                final com.hederahashgraph.api.proto.java.AccountID nodeAccountId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TargetNetworkType type() {
            return TargetNetworkType.SUBPROCESS_NETWORK;
        }

        @Override
        public List<HederaNode> nodes() {
            return List.of(node);
        }

        @Override
        public String name() {
            return "test-network";
        }

        @Override
        public void start() {
            // No-op
        }

        @Override
        public void terminate() {
            // No-op
        }

        @Override
        public void awaitReady(final Duration timeout) {
            // No-op
        }

        @Override
        public PrometheusClient prometheusClient() {
            return new PrometheusClient();
        }
    }

    private record TestNode(String name, Path applicationLog) implements HederaNode {
        @Override
        public String getHost() {
            return "localhost";
        }

        @Override
        public int getGrpcPort() {
            return 0;
        }

        @Override
        public int getGrpcNodeOperatorPort() {
            return 0;
        }

        @Override
        public long getNodeId() {
            return 0;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public AccountID getAccountId() {
            return AccountID.DEFAULT;
        }

        @Override
        public Path getExternalPath(final ExternalPath path) {
            return applicationLog;
        }

        @Override
        public HederaNode initWorkingDir(final Network network) {
            return this;
        }

        @Override
        public HederaNode start() {
            return this;
        }

        @Override
        public CompletableFuture<Void> statusFuture(
                final Consumer<NodeStatus> nodeStatusObserver, final PlatformStatus... statuses) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> minLogsFuture(final String pattern, final int n) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> mfFuture(final MarkerFile markerFile) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> stopFuture() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public NodeMetadata metadata() {
            return null;
        }
    }
}

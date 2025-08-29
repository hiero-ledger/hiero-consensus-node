// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera;

import static com.hedera.services.bdd.junit.hedera.ExternalPath.DATA_CONFIG_DIR;
import static com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork.findAvailablePort;

import com.hedera.node.internal.network.BlockNodeConfig;
import com.hedera.node.internal.network.BlockNodeConnectionInfo;
import com.hedera.services.bdd.junit.hedera.containers.BlockNodeContainer;
import com.hedera.services.bdd.junit.hedera.simulator.BlockNodeSimulatorController;
import com.hedera.services.bdd.junit.hedera.simulator.SimulatedBlockNodeServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BlockNodeNetwork {

    private static final Logger logger = LogManager.getLogger(BlockNodeNetwork.class);

    // Block Node Configuration maps
    private final Map<Long, BlockNodeMode> blockNodeModeById = new HashMap<>();
    private final Map<Long, SimulatedBlockNodeServer> simulatedBlockNodeById = new HashMap<>();
    private final Map<Long, BlockNodeContainer> blockNodeContainerById = new HashMap<>();

    // SubProcessNode configuration for Block Nodes (just priorities for now)
    private final Map<Long, long[]> blockNodePrioritiesBySubProcessNodeId = new HashMap<>();
    private final Map<Long, long[]> blockNodeIdsBySubProcessNodeId = new HashMap<>();

    public static final int BLOCK_NODE_LOCAL_PORT = 40840;

    private BlockNodeSimulatorController blockNodeSimulatorController;

    public BlockNodeNetwork() {
        // Initialize the Block Node Simulator Controller
        this.blockNodeSimulatorController = new BlockNodeSimulatorController(this);
    }

    public void start() {
        if (!blockNodeModeById.isEmpty()) {
            logger.info("Starting Block Node Network with the following Block Node configurations:");
            // Log the configurations for each Block Node (sim or real/local node)
            for (Map.Entry<Long, BlockNodeMode> entry : blockNodeModeById.entrySet()) {
                long nodeId = entry.getKey();
                BlockNodeMode mode = entry.getValue();
                logger.info("Block Node ID: {}, Block Node Mode: {}", nodeId, mode);
            }
            // Log the configurations for each SubProcessNode in the Shared SubProcessNetwork
            for (Map.Entry<Long, long[]> entry : blockNodeIdsBySubProcessNodeId.entrySet()) {
                long nodeId = entry.getKey();
                long[] priorities = blockNodePrioritiesBySubProcessNodeId.get(nodeId);
                long[] blockNodeIds = entry.getValue();
                logger.info(
                        "SubProcessNode ID: {}, Block Node IDs: {}, Priorities: {}",
                        nodeId,
                        Arrays.toString(blockNodeIds),
                        Arrays.toString(priorities));
            }
        }

        // First start block nodes if needed
        startBlockNodesAsApplicable();
    }

    public void terminate() {
        // Stop block node containers
        for (Entry<Long, BlockNodeContainer> entry : blockNodeContainerById.entrySet()) {
            BlockNodeContainer container = entry.getValue();
            container.stop();
            logger.info("Stopped block node container ID {}", entry.getKey());
        }
        blockNodeContainerById.clear();

        // Stop simulated block nodes with grace period
        Duration shutdownTimeout = Duration.ofSeconds(30);
        logger.info(
                "Gracefully stopping {} simulated block nodes with {} timeout",
                simulatedBlockNodeById.size(),
                shutdownTimeout);

        List<CompletableFuture<Void>> shutdownFutures = new ArrayList<>();
        for (Entry<Long, SimulatedBlockNodeServer> entry : simulatedBlockNodeById.entrySet()) {
            final SimulatedBlockNodeServer server = entry.getValue();
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    server.stop();
                    logger.info("Successfully stopped simulated block node on port {}", server.getPort());
                } catch (Exception e) {
                    logger.error("Error stopping simulated block node on port {}", server.getPort(), e);
                }
            });
            shutdownFutures.add(future);
        }

        try {
            // Wait for all servers to stop or timeout
            CompletableFuture.allOf(shutdownFutures.toArray(new CompletableFuture[0]))
                    .get(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
            logger.info("All simulated block nodes stopped successfully");
        } catch (Exception e) {
            logger.error("Timeout or error while stopping simulated block nodes", e);
        }
        simulatedBlockNodeById.clear();
    }

    private void startBlockNodesAsApplicable() {
        for (Map.Entry<Long, BlockNodeMode> entry : blockNodeModeById.entrySet()) {
            if (entry.getValue() == BlockNodeMode.REAL) {
                // TODO
            } else if (entry.getValue() == BlockNodeMode.SIMULATOR) {
                addSimulatorNode(entry.getKey(), null);
            }
        }
    }

    public void addSimulatorNode(Long id, Supplier<Long> lastVerifiedBlockNumberSupplier) {
        // Find an available port
        int port = findAvailablePort();
        final SimulatedBlockNodeServer server = new SimulatedBlockNodeServer(port, lastVerifiedBlockNumberSupplier);
        try {
            server.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start simulated block node on port " + port, e);
        }
        logger.info("Started shared simulated block node @ localhost:{}", port);
        simulatedBlockNodeById.put(id, server);
    }

    public void configureBlockNodeConnectionInformation(HederaNode node) {
        List<BlockNodeConfig> blockNodes = new ArrayList<>();
        long[] blockNodeIds = blockNodeIdsBySubProcessNodeId.get(node.getNodeId());
        if (blockNodeIds == null) {
            logger.info("No block nodes configured for node {}", node.getNodeId());
            return;
        }
        for (int blockNodeIndex = 0; blockNodeIndex < blockNodeIds.length; blockNodeIndex++) {
            long blockNodeId = blockNodeIds[blockNodeIndex];
            BlockNodeMode mode = blockNodeModeById.get(blockNodeId);
            if (mode == BlockNodeMode.REAL) {
                throw new UnsupportedOperationException("Real block nodes are not supported yet");
            } else if (mode == BlockNodeMode.SIMULATOR) {
                final SimulatedBlockNodeServer sim = simulatedBlockNodeById.get(blockNodeId);
                int priority = (int) blockNodePrioritiesBySubProcessNodeId.get(node.getNodeId())[blockNodeIndex];
                blockNodes.add(new BlockNodeConfig("localhost", sim.getPort(), priority));
            } else if (mode == BlockNodeMode.LOCAL_NODE) {
                blockNodes.add(new BlockNodeConfig("localhost", BLOCK_NODE_LOCAL_PORT, 0));
            }
        }
        if (!blockNodes.isEmpty()) {
            BlockNodeConnectionInfo connectionInfo = new BlockNodeConnectionInfo(blockNodes);
            try {
                // Write the config to this consensus node's block-nodes.json
                Path configPath = node.getExternalPath(DATA_CONFIG_DIR).resolve("block-nodes.json");
                Files.writeString(configPath, BlockNodeConnectionInfo.JSON.toJSON(connectionInfo));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        logger.info("Configured block node connection information for node {}: {}", node.getNodeId(), blockNodes);
    }

    public Map<Long, BlockNodeMode> getBlockNodeModeById() {
        return blockNodeModeById;
    }

    public Set<Long> nodeIds() {
        return getBlockNodeModeById().keySet();
    }

    public Map<Long, SimulatedBlockNodeServer> getSimulatedBlockNodeById() {
        return simulatedBlockNodeById;
    }

    public Map<Long, BlockNodeContainer> getBlockNodeContainerById() {
        return blockNodeContainerById;
    }

    public Map<Long, long[]> getBlockNodePrioritiesBySubProcessNodeId() {
        return blockNodePrioritiesBySubProcessNodeId;
    }

    public Map<Long, long[]> getBlockNodeIdsBySubProcessNodeId() {
        return blockNodeIdsBySubProcessNodeId;
    }

    public BlockNodeSimulatorController getBlockNodeSimulatorController() {
        return blockNodeSimulatorController;
    }
}

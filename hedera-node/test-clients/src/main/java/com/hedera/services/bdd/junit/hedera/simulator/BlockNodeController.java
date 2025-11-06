// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.simulator;

import com.hedera.services.bdd.junit.hedera.BlockNodeNetwork;
import com.hedera.services.bdd.junit.hedera.containers.BlockNodeContainer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.PublishStreamResponse.EndOfStream;

/**
 * A utility class to control simulated block node servers in a SubProcessNetwork.
 * This allows tests to induce specific response codes for testing error handling and edge cases.
 */
public class BlockNodeController {
    private static final Logger log = LogManager.getLogger(BlockNodeController.class);
    private static Map<Long, SimulatedBlockNodeServer> simulatedBlockNodes = new HashMap<>();
    private static Map<Long, BlockNodeContainer> blockNodeContainers = new HashMap<>();
    // Store the ports of shutdown block nodes for restart
    private static final Map<Long, Integer> shutdownBlockNodePorts = new HashMap<>();
    private static final Map<Long, Long> lastVerifiedBlockNumbers = new HashMap<>();
    private static final Set<Long> persistentStateBlockNodes = new HashSet<>();

    /**
     * Create a controller for the given network's simulated block nodes.
     *
     * @param network the SubProcessNetwork containing simulated block nodes
     */
    public BlockNodeController(@NonNull final BlockNodeNetwork network) {
        simulatedBlockNodes = network.getSimulatedBlockNodeById();
        if (simulatedBlockNodes.isEmpty()) {
            log.warn("No simulated block nodes found in the network. Make sure BlockNodeMode.SIMULATOR is set.");
        } else {
            log.info("Controlling {} simulated block nodes", simulatedBlockNodes.size());
        }

        blockNodeContainers = network.getBlockNodeContainerById();
        if (blockNodeContainers.isEmpty()) {
            log.warn("No block nodes containers found in the network. Make sure BlockNodeMode.REAL is set.");
        } else {
            log.info("Controlling {} containerized block nodes", blockNodeContainers.size());
        }
    }

    /**
     * Configure all simulated block nodes to respond with a specific EndOfStream response code.
     *
     * @param responseCode the response code to send
     * @param blockNumber the block number to include in the response
     */
    public void setEndOfStreamResponse(final EndOfStream.Code responseCode, final long blockNumber) {
        for (final SimulatedBlockNodeServer server : simulatedBlockNodes.values()) {
            server.setEndOfStreamResponse(responseCode, blockNumber);
        }
        log.info("Set EndOfStream response code {} for block {} on all simulators", responseCode, blockNumber);
    }

    /**
     * Configure a specific simulated block node to respond with a specific EndOfStream response code.
     *
     * @param index the index of the simulated block node (0-based)
     * @param responseCode the response code to send
     * @param blockNumber the block number to include in the response
     */
    public void setEndOfStreamResponse(final long index, final EndOfStream.Code responseCode, final long blockNumber) {
        if (index >= 0 && index < simulatedBlockNodes.size()) {
            simulatedBlockNodes.get(index).setEndOfStreamResponse(responseCode, blockNumber);
            log.info("Set EndOfStream response code {} for block {} on simulator {}", responseCode, blockNumber, index);
        } else {
            log.error("Invalid simulator index: {}, valid range is 0-{}", index, simulatedBlockNodes.size() - 1);
        }
    }

    /**
     * Send an EndOfStream response immediately to all active streams on all simulated block nodes.
     * This will end all active streams with the specified response code.
     *
     * @param responseCode the response code to send
     * @param blockNumber the block number to include in the response
     * @return the last verified block number from the first simulator
     */
    public long sendEndOfStreamImmediately(final EndOfStream.Code responseCode, final long blockNumber) {
        long lastVerifiedBlockNumber = 0L;
        for (long i = 0; i < simulatedBlockNodes.size(); i++) {
            final SimulatedBlockNodeServer server = simulatedBlockNodes.get(i);
            final long serverLastVerified = server.sendEndOfStreamImmediately(responseCode, blockNumber);
            if (i == 0) {
                lastVerifiedBlockNumber = serverLastVerified;
            }
        }
        log.info(
                "Sent immediate EndOfStream response with code {} for block {} on all simulators, last verified block: {}",
                responseCode,
                blockNumber,
                lastVerifiedBlockNumber);
        return lastVerifiedBlockNumber;
    }

    /**
     * Send an EndOfStream response immediately to all active streams on a specific simulated block node.
     * This will end all active streams with the specified response code.
     *
     * @param index the index of the simulated block node (0-based)
     * @param responseCode the response code to send
     * @param blockNumber the block number to include in the response
     * @return the last verified block number from the simulator, or 0 if the index is invalid
     */
    public long sendEndOfStreamImmediately(
            final long index, final EndOfStream.Code responseCode, final long blockNumber) {
        long lastVerifiedBlockNumber = 0L;
        if (index >= 0 && index < simulatedBlockNodes.size()) {
            final SimulatedBlockNodeServer server = simulatedBlockNodes.get(index);
            lastVerifiedBlockNumber = server.sendEndOfStreamImmediately(responseCode, blockNumber);
            log.info(
                    "Sent immediate EndOfStream response with code {} for block {} on simulator {}, last verified block: {}",
                    responseCode,
                    blockNumber,
                    index,
                    lastVerifiedBlockNumber);
        } else {
            log.error("Invalid simulator index: {}, valid range is 0-{}", index, simulatedBlockNodes.size() - 1);
        }
        return lastVerifiedBlockNumber;
    }

    /**
     * Send a SkipBlock response immediately to all active streams on all simulated block nodes.
     * This will instruct all active streams to skip the specified block.
     *
     * @param blockNumber the block number to skip
     */
    public void sendSkipBlockImmediately(final long blockNumber) {
        for (final SimulatedBlockNodeServer server : simulatedBlockNodes.values()) {
            server.sendSkipBlockImmediately(blockNumber);
        }
        log.info("Sent immediate SkipBlock response for block {} on all simulators", blockNumber);
    }

    /**
     * Send a SkipBlock response immediately to all active streams on a specific simulated block node.
     * This will instruct all active streams to skip the specified block.
     *
     * @param index the index of the simulated block node (0-based)
     * @param blockNumber the block number to skip
     */
    public void sendSkipBlockImmediately(final long index, final long blockNumber) {
        if (index >= 0 && index < simulatedBlockNodes.size()) {
            final SimulatedBlockNodeServer server = simulatedBlockNodes.get(index);
            server.sendSkipBlockImmediately(blockNumber);
            log.info("Sent immediate SkipBlock response for block {} on simulator {}", blockNumber, index);
        } else {
            log.error("Invalid simulator index: {}, valid range is 0-{}", index, simulatedBlockNodes.size() - 1);
        }
    }

    /**
     * Send a ResendBlock response immediately to all active streams on all simulated block nodes.
     * This will instruct all active streams to resend the specified block.
     *
     * @param blockNumber the block number to resend
     */
    public void sendResendBlockImmediately(final long blockNumber) {
        for (final SimulatedBlockNodeServer server : simulatedBlockNodes.values()) {
            server.sendResendBlockImmediately(blockNumber);
        }
        log.info("Sent immediate ResendBlock response for block {} on all simulators", blockNumber);
    }

    /**
     * Send a ResendBlock response immediately to all active streams on a specific simulated block node.
     * This will instruct all active streams to resend the specified block.
     *
     * @param index the index of the simulated block node (0-based)
     * @param blockNumber the block number to resend
     */
    public void sendResendBlockImmediately(final long index, final long blockNumber) {
        if (index >= 0 && index < simulatedBlockNodes.size()) {
            final SimulatedBlockNodeServer server = simulatedBlockNodes.get(index);
            server.sendResendBlockImmediately(blockNumber);
            log.info("Sent immediate ResendBlock response for block {} on simulator {}", blockNumber, index);
        } else {
            log.error("Invalid simulator index: {}, valid range is 0-{}", index, simulatedBlockNodes.size() - 1);
        }
    }

    /**
     * Reset all configured responses on all simulated block nodes to default behavior.
     */
    public void resetAllResponses() {
        for (final SimulatedBlockNodeServer server : simulatedBlockNodes.values()) {
            server.resetResponses();
        }
        log.info("Reset all responses on all simulators to default behavior");
    }

    /**
     * Reset all configured responses on a specific simulated block node to default behavior.
     *
     * @param index the index of the simulated block node (0-based)
     */
    public void resetResponses(final long index) {
        if (index >= 0 && index < simulatedBlockNodes.size()) {
            simulatedBlockNodes.get(index).resetResponses();
            log.info("Reset all responses on simulator {} to default behavior", index);
        } else {
            log.error("Invalid simulator index: {}, valid range is 0-{}", index, simulatedBlockNodes.size() - 1);
        }
    }

    /**
     * Shutdown all simulated block nodes to simulate connection drops.
     * The servers can be restarted using {@link #startAllSimulators()}.
     */
    public void shutdownAllSimulators(final boolean persistState) {
        if (persistState) {
            persistentStateBlockNodes.addAll(blockNodeContainers.keySet());
        } else {
            blockNodeContainers.clear();
        }
        shutdownBlockNodePorts.clear();
        for (final Map.Entry<Long, SimulatedBlockNodeServer> entry : simulatedBlockNodes.entrySet()) {
            final long nodeId = entry.getKey();
            shutdownSimulator(nodeId, persistState);
        }
        log.info("Shutdown all {} simulators to simulate connection drops", simulatedBlockNodes.size());
    }

    /**
     * Shutdown a specific simulated block node to simulate a connection drop.
     * The server can be restarted using {@link #startSimulator(long)}.
     *
     * @param nodeId the index of the simulated block node (0-based)
     */
    public void shutdownSimulator(long nodeId, final boolean persistState) {
        if (nodeId >= 0 && nodeId < simulatedBlockNodes.size()) {
            final SimulatedBlockNodeServer server = simulatedBlockNodes.get(nodeId);
            final int port = server.getPort();

            shutdownBlockNodePorts.put(nodeId, port);

            if (persistState) {
                persistentStateBlockNodes.add(nodeId);
                lastVerifiedBlockNumbers.put(nodeId, server.getLastVerifiedBlockNumber());
            } else {
                persistentStateBlockNodes.remove(nodeId);
                lastVerifiedBlockNumbers.put(nodeId, -1L);
            }

            server.stop();
            log.info("Shutdown simulator {} on port {} to simulate connection drop", nodeId, port);
        } else {
            log.error("Invalid simulator node id: {}, valid range is 0-{}", nodeId, simulatedBlockNodes.size() - 1);
        }
    }

    /**
     * Start all previously shutdown simulated block nodes.
     * This will recreate the servers on the same ports they were running on before shutdown.
     *
     * @throws IOException if a server fails to start
     */
    public void startAllSimulators() throws IOException {
        for (final Entry<Long, Integer> entry : shutdownBlockNodePorts.entrySet()) {
            final long index = entry.getKey();
            startSimulator(index);
            shutdownBlockNodePorts.remove(index);
        }

        log.info("Started simulators");
    }

    /**
     * Start a specific previously shutdown simulated block node.
     * This will recreate the server on the same port it was running on before shutdown.
     *
     * @param nodeId the nodeId of the simulated block node (0-based)
     * @throws IOException if the server fails to start
     */
    public void startSimulator(final long nodeId) throws IOException {
        if (!shutdownBlockNodePorts.containsKey(nodeId)) {
            log.error("Simulator {} was not previously shutdown or has already been restarted", nodeId);
            return;
        }

        if (nodeId >= 0 && nodeId < simulatedBlockNodes.size()) {
            final int port = shutdownBlockNodePorts.get(nodeId);

            // Create a new server on the same port
            final long lastVerifiedBlockNumber = persistentStateBlockNodes.contains(nodeId)
                    ? lastVerifiedBlockNumbers.getOrDefault(nodeId, -1L)
                    : -1L;
            final SimulatedBlockNodeServer newServer =
                    new SimulatedBlockNodeServer(port, false, () -> lastVerifiedBlockNumber);
            newServer.start();

            // Replace the old server in the list
            simulatedBlockNodes.put(nodeId, newServer);

            // Remove from the shutdown map
            shutdownBlockNodePorts.remove(nodeId);

            log.info("Restarted simulator {} on port {}", nodeId, port);
        } else {
            log.error("Invalid simulator node id: {}, valid range is 0-{}", nodeId, simulatedBlockNodes.size() - 1);
        }
    }

    /**
     * Get the last verified block number from a specific simulated block node.
     *
     * @param index the index of the simulated block node (0-based)
     * @return the last verified block number, or 0 if the index is invalid
     */
    public long getLastVerifiedBlockNumber(final long index) {
        if (index >= 0 && index < simulatedBlockNodes.size()) {
            return simulatedBlockNodes.get(index).getLastVerifiedBlockNumber();
        } else {
            log.error("Invalid simulator index: {}, valid range is 0-{}", index, simulatedBlockNodes.size() - 1);
            return 0L;
        }
    }

    /**
     * Check if a specific block number has been received by a specific simulator.
     *
     * @param index the index of the simulated block node (0-based)
     * @param blockNumber the block number to check
     * @return true if the block has been received, false otherwise
     * @throws IllegalArgumentException if the simulator index is invalid
     */
    public boolean hasReceivedBlock(final long index, final long blockNumber) {
        if (index < 0 || index >= simulatedBlockNodes.size()) {
            throw new IllegalArgumentException(
                    "Invalid simulator index: " + index + ", valid range is 0-" + (simulatedBlockNodes.size() - 1));
        }

        final SimulatedBlockNodeServer server = simulatedBlockNodes.get(index);
        return server.hasReceivedBlock(blockNumber);
    }

    /**
     * Check if a specific block number has been received by any simulator.
     *
     * @param blockNumber the block number to check
     * @return true if the block has been received by any simulator, false otherwise
     */
    public boolean hasAnyReceivedBlock(final long blockNumber) {
        for (final SimulatedBlockNodeServer server : simulatedBlockNodes.values()) {
            if (server.hasReceivedBlock(blockNumber)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get all block numbers that have been received by a specific simulator.
     *
     * @param index the index of the simulated block node (0-based)
     * @return a set of all received block numbers
     * @throws IllegalArgumentException if the simulator index is invalid
     */
    public Set<Long> getReceivedBlockNumbers(final long index) {
        if (index < 0 || index >= simulatedBlockNodes.size()) {
            throw new IllegalArgumentException(
                    "Invalid simulator index: " + index + ", valid range is 0-" + (simulatedBlockNodes.size() - 1));
        }

        final SimulatedBlockNodeServer server = simulatedBlockNodes.get(index);
        return server.getReceivedBlockNumbers();
    }

    /**
     * Check if a specific block node has been shut down.
     *
     * @param index the index of the block node (0-based)
     * @return true if the block node has been shut down, false otherwise
     */
    public boolean isBlockNodeShutdown(final long index) {
        return shutdownBlockNodePorts.containsKey(index);
    }

    /**
     * Check if any simulators have been shut down.
     *
     * @return true if any simulators have been shut down, false otherwise
     */
    public boolean areAnyBlockNodesBeenShutdown() {
        return !shutdownBlockNodePorts.isEmpty();
    }

    /**
     * Start a previous shutdown block node container.
     * This will recreate the container on the same port it was running on before shutdown.
     * *
     * @param nodeIndex the index of the block node to be started
     */
    public void startContainer(final long nodeIndex) {
        if (!shutdownBlockNodePorts.containsKey(nodeIndex)) {
            log.error("Block Node container {} was not previously shutdown or has already been restarted", nodeIndex);
            return;
        }

        if (nodeIndex >= 0 && nodeIndex < blockNodeContainers.size()) {
            final int port = shutdownBlockNodePorts.get(nodeIndex);
            final BlockNodeContainer blockNodeContainer;

            if (persistentStateBlockNodes.contains(nodeIndex)) {
                blockNodeContainer = blockNodeContainers.get(nodeIndex);
                blockNodeContainer.resume();
            } else {
                blockNodeContainer = new BlockNodeContainer(nodeIndex, port);
                blockNodeContainer.start();
            }

            log.info("Started container {} @ {} and waited for readiness", nodeIndex, blockNodeContainer);
            blockNodeContainers.put(nodeIndex, blockNodeContainer);
            shutdownBlockNodePorts.remove(nodeIndex);
        } else {
            log.error("Invalid container index: {}, valid range is 0-{}", nodeIndex, blockNodeContainers.size() - 1);
        }
    }

    /**
     * Shutdown a specific block node container to simulate a connection drop.
     *
     * @param nodeId the index of the block node to be shutdown
     */
    public void shutdownContainer(final long nodeId, final boolean persistState) {
        if (nodeId >= 0 && nodeId < blockNodeContainers.size()) {
            final BlockNodeContainer shutdownContainer = blockNodeContainers.get(nodeId);
            log.info("Shutting down container {} @ {}", nodeId, shutdownContainer);

            shutdownBlockNodePorts.put(nodeId, shutdownContainer.getPort());

            if (persistState) {
                persistentStateBlockNodes.add(nodeId);
                shutdownContainer.pause();
            } else {
                persistentStateBlockNodes.remove(nodeId);
                shutdownContainer.stop();
            }

            log.info("Container {} shutdown complete", nodeId);
        } else {
            log.error("Invalid container index: {}, valid range is 0-{}", nodeId, blockNodeContainers.size() - 1);
        }
    }

    /**
     * Updates whether block acknowledgements should be sent from the specified block node.
     *
     * @param nodeIdx the index of the block node to update (0-based)
     * @param sendBlockAcknowledgementsEnabled true if block acknowledgements should be sent from the simulator node, otherwise they will not
     */
    public void setSendBlockAcknowledgementsEnabled(
            final long nodeIdx, final boolean sendBlockAcknowledgementsEnabled) {
        simulatedBlockNodes.get(nodeIdx).setSendingBlockAcknowledgementsEnabled(sendBlockAcknowledgementsEnabled);
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.remote;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.sloth.fixtures.Node;
import org.hiero.sloth.fixtures.TimeManager;
import org.hiero.sloth.fixtures.internal.AbstractNetwork;
import org.hiero.sloth.fixtures.internal.RegularTimeManager;
import org.hiero.sloth.fixtures.internal.network.ConnectionKey;
import org.hiero.sloth.fixtures.network.Topology.ConnectionState;

/**
 * An implementation of {@link org.hiero.sloth.fixtures.Network} for a remote SSH environment. Nodes are deployed and
 * executed on remote machines via SSH, with gRPC communication tunneled through SSH port forwarding.
 */
public class RemoteNetwork extends AbstractNetwork {

    private static final Logger log = LogManager.getLogger();

    private final RegularTimeManager timeManager;
    private final Path rootOutputDirectory;
    private final Executor executor = Executors.newCachedThreadPool();
    private final RemoteHostAllocator hostAllocator;
    private final Map<String, SshExecutor> sshExecutors = new HashMap<>();
    private final Map<String, String> hostIpAddresses = new HashMap<>();
    private final String remoteWorkDir;
    private final String remoteJavaPath;
    private final boolean cleanupOnDestroy;

    /**
     * Constructor for {@link RemoteNetwork}.
     *
     * @param timeManager the time manager to use
     * @param rootOutputDirectory the root output directory for the network
     * @param useRandomNodeIds {@code true} if the node IDs should be selected randomly
     * @param hosts the list of SSH host names
     * @param remoteWorkDir the base working directory on the remote hosts
     * @param cleanupOnDestroy whether to clean up remote files after test completion
     * @param remoteJavaPath path to the Java executable on remote hosts
     */
    public RemoteNetwork(
            @NonNull final RegularTimeManager timeManager,
            @NonNull final Path rootOutputDirectory,
            final boolean useRandomNodeIds,
            @NonNull final List<String> hosts,
            @NonNull final String remoteWorkDir,
            final boolean cleanupOnDestroy,
            @NonNull final String remoteJavaPath) {
        super(new Random(), useRandomNodeIds);
        this.timeManager = requireNonNull(timeManager);
        this.rootOutputDirectory = requireNonNull(rootOutputDirectory);
        this.hostAllocator = new RemoteHostAllocator(hosts);
        this.remoteWorkDir = requireNonNull(remoteWorkDir);
        this.cleanupOnDestroy = cleanupOnDestroy;
        this.remoteJavaPath = requireNonNull(remoteJavaPath);

        // Verify connectivity and resolve IP addresses for all hosts
        for (final String host : hosts) {
            final SshExecutor ssh = getOrCreateSshExecutor(host);
            ssh.verifyConnectivity();
            final String ip = resolveHostIp(ssh, host);
            hostIpAddresses.put(host, ip);
            log.info("Resolved {} -> {}", host, ip);
        }
    }

    /**
     * Resolves the public/reachable IP address of a remote host by querying it via SSH. This IP is used in the roster
     * so that nodes can establish gossip connections to each other.
     */
    @NonNull
    private static String resolveHostIp(@NonNull final SshExecutor ssh, @NonNull final String host) {
        // hostname -I returns all IP addresses; take the first non-loopback one
        final SshExecutor.ExecResult result = ssh.exec("hostname", "-I");
        if (result.exitCode() == 0 && !result.stdout().isBlank()) {
            final String ip = result.stdout().trim().split("\\s+")[0];
            if (!ip.isEmpty()) {
                return ip;
            }
        }
        throw new IllegalStateException("Failed to resolve IP address for host " + host + ": exit=" + result.exitCode()
                + " out=" + result.stdout() + " err=" + result.stderr());
    }

    @Override
    @NonNull
    protected TimeManager timeManager() {
        return timeManager;
    }

    @Override
    protected void onConnectionsChanged(@NonNull final Map<ConnectionKey, ConnectionState> connections) {
        // Remote environment does not support proxy-based network simulation
    }

    @Override
    @NonNull
    protected RemoteNode doCreateNode(@NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
        final RemoteHostAllocator.HostAssignment assignment = hostAllocator.allocate(nodeId);
        final SshExecutor sshExecutor = getOrCreateSshExecutor(assignment.host());
        final Path outputDir = rootOutputDirectory.resolve(NODE_IDENTIFIER_FORMAT.formatted(nodeId.id()));

        final RemoteNode node = new RemoteNode(
                nodeId,
                timeManager,
                keysAndCerts,
                sshExecutor,
                assignment,
                outputDir,
                networkConfiguration,
                remoteWorkDir,
                remoteJavaPath,
                cleanupOnDestroy);
        timeManager.addTimeTickReceiver(node);
        return node;
    }

    /**
     * Rewrites the roster to use real IP addresses and assigned gossip ports instead of the default "node-N" domain
     * names. The IP addresses are resolved during construction by querying each remote host via SSH. This is necessary
     * because nodes need actual routable addresses for gossip communication.
     */
    @Override
    protected void preStartHook(@NonNull final Roster preliminaryRoster) {
        final List<RosterEntry> correctedEntries = preliminaryRoster.rosterEntries().stream()
                .map(entry -> {
                    final NodeId nodeId = NodeId.of(entry.nodeId());
                    final RemoteHostAllocator.HostAssignment assignment = hostAllocator.getAssignment(nodeId);
                    final String ip = hostIpAddresses.get(assignment.host());
                    log.info("Roster entry for node {}: {}:{}", nodeId, ip, assignment.gossipPort());
                    return entry.copyBuilder()
                            .gossipEndpoint(ServiceEndpoint.newBuilder()
                                    .domainName(ip)
                                    .port(assignment.gossipPort())
                                    .build())
                            .build();
                })
                .toList();
        roster = Roster.newBuilder().rosterEntries(correctedEntries).build();
    }

    @Override
    protected void doSendQuiescenceCommand(@NonNull final QuiescenceCommand command, @NonNull final Duration timeout) {
        for (final Node node : nodes()) {
            executor.execute(() -> node.withTimeout(timeout).sendQuiescenceCommand(command));
        }
    }

    /**
     * Shuts down the network and cleans up all resources. Downloads artifacts from remote hosts, kills remote
     * processes, and optionally removes remote files.
     */
    void destroy() {
        log.info("Destroying remote network...");
        nodes().forEach(node -> ((RemoteNode) node).destroy());
    }

    @NonNull
    private SshExecutor getOrCreateSshExecutor(@NonNull final String host) {
        return sshExecutors.computeIfAbsent(host, SshExecutor::new);
    }
}

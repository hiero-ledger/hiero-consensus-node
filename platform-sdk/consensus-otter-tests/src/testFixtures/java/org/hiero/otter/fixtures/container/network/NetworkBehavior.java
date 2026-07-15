// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container.network;

import static org.hiero.otter.fixtures.network.BandwidthLimit.UNLIMITED_BANDWIDTH;
import static org.hiero.otter.fixtures.network.Topology.DISCONNECTED;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.assertj.core.data.Percentage;
import org.hiero.consensus.gossip.config.NetworkEndpoint;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.container.network.Toxin.BandwidthToxin;
import org.hiero.otter.fixtures.container.network.Toxin.LatencyToxin;
import org.hiero.otter.fixtures.exceptions.NetworkControlUnavailableException;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.network.Topology.ConnectionState;

/**
 * This class is a wrapper around the Toxiproxy client and provides methods to modify the network behavior.
 */
public class NetworkBehavior {

    private static final Logger log = LogManager.getLogger();

    private static final ConnectionState INITIAL_STATE =
            new ConnectionState(true, Duration.ZERO, Percentage.withPercentage(0), UNLIMITED_BANDWIDTH);

    private final ToxiproxyClient toxiproxyClient;
    private final Map<ConnectionKey, Proxy> proxies = new HashMap<>();
    private Map<ConnectionKey, ConnectionState> connections = new HashMap<>();

    /**
     * Constructs a new NetworkBehavior instance using the Toxiproxy client.
     *
     * @param host               the host on which the Toxiproxy control server is running
     * @param controlPort        the port on which the Toxiproxy control server is running
     * @param roster             the roster containing the nodes in the network
     * @param toxiproxyIpAddress the IP address of the Toxiproxy container in the Docker network
     */
    public NetworkBehavior(
            @NonNull final String host,
            final int controlPort,
            @NonNull final Roster roster,
            @NonNull final String toxiproxyIpAddress) {
        toxiproxyClient = new ToxiproxyClient(host, controlPort);

        final String listenAddress = toxiproxyIpAddress + ":0";

        final List<NodeId> nodeIds =
                roster.rosterEntries().stream().map(RosterUtils::getNodeId).toList();
        final Toxin upstreamLatencyToxin = new LatencyToxin(INITIAL_STATE.latency(), INITIAL_STATE.jitter());
        final Toxin downstreamLatencyToxin = upstreamLatencyToxin.downstream();
        final Toxin upstreamBandwidthToxin = new BandwidthToxin(INITIAL_STATE.bandwidthLimit());
        final Toxin downstreamBandwidthToxin = upstreamBandwidthToxin.downstream();
        for (final RosterEntry receiverEntry : roster.rosterEntries()) {
            final NodeId receiver = NodeId.of(receiverEntry.nodeId());
            final ServiceEndpoint endpoint = receiverEntry.gossipEndpoint().getFirst();
            final String receiverAddress = "%s:%d".formatted(endpoint.domainName(), endpoint.port());
            for (final NodeId sender : nodeIds) {
                if (sender.equals(receiver)) {
                    continue;
                }
                log.debug(
                        "Creating connection between sender {} and receiver {} at address {}",
                        sender,
                        receiver,
                        receiverAddress);

                final ConnectionKey connectionKey = new ConnectionKey(sender, receiver);
                final String connectionName = "%d-%d".formatted(sender.id(), receiver.id());
                final Proxy proxy = new Proxy(connectionName, listenAddress, receiverAddress, true);
                proxies.put(connectionKey, toxiproxyClient.createProxy(proxy));
                toxiproxyClient.createToxin(proxy, upstreamLatencyToxin);
                toxiproxyClient.createToxin(proxy, downstreamLatencyToxin);
                toxiproxyClient.createToxin(proxy, upstreamBandwidthToxin);
                toxiproxyClient.createToxin(proxy, downstreamBandwidthToxin);
                connections.put(connectionKey, INITIAL_STATE);
            }
        }
    }

    /**
     * Updates the connections in the network based on the provided nodes and connection data.
     *
     * @param nodes          the list of nodes in the network
     * @param newConnections a map of connections representing the current state of the network
     */
    public void onConnectionsChanged(
            @NonNull final List<Node> nodes, @NonNull final Map<ConnectionKey, ConnectionState> newConnections) {
        // Publish the target state before applying it, so that if a mutation wedges and triggers recovery, the
        // recreated proxy is rebuilt with the intended toxics rather than the state we are moving away from.
        final Map<ConnectionKey, ConnectionState> oldConnections = connections;
        connections = newConnections;
        for (final Node sender : nodes) {
            for (final Node receiver : nodes) {
                if (sender.equals(receiver)) {
                    continue; // Skip self-connections
                }
                final ConnectionKey connectionKey = new ConnectionKey(sender.selfId(), receiver.selfId());
                final ConnectionState oldConnectionState = oldConnections.getOrDefault(connectionKey, DISCONNECTED);
                final ConnectionState newConnectionState = newConnections.getOrDefault(connectionKey, DISCONNECTED);
                if (newConnectionState.connected()) {
                    if (!oldConnectionState.connected()) {
                        connect(connectionKey);
                    }
                    if (!Objects.equals(oldConnectionState.latency(), newConnectionState.latency())
                            || !Objects.equals(oldConnectionState.jitter(), newConnectionState.jitter())) {
                        setLatency(connectionKey, newConnectionState);
                    }
                    if (!Objects.equals(oldConnectionState.bandwidthLimit(), newConnectionState.bandwidthLimit())) {
                        setBandwidthLimit(connectionKey, newConnectionState);
                    }
                } else {
                    if (oldConnectionState.connected()) {
                        disconnect(connectionKey);
                    }
                }
            }
        }
    }

    /**
     * Recreates every proxy from scratch so that it matches the given target state. This restores a clean, toxic-free
     * network after chaos: because each proxy is deleted and created afresh (rather than having its toxics updated in
     * place, which can wedge), no proxy can be left in a wedged state, and the result is guaranteed to reflect exactly
     * the target state.
     *
     * @param newConnections the target state that every proxy should be recreated to match
     */
    public void recreateAllProxies(@NonNull final Map<ConnectionKey, ConnectionState> newConnections) {
        connections = newConnections;
        for (final ConnectionKey connectionKey : proxies.keySet()) {
            recreateProxy(connectionKey);
        }
    }

    /**
     * Recreates the proxy for the given connection from scratch: it is deleted &ndash; which escapes any wedge and
     * unblocks a stuck toxic goroutine &ndash; and then created afresh with its toxics reapplied at the current intended
     * state. Deleting first frees the listen port, so the new proxy keeps the same name, listen address, and upstream,
     * and nodes reconnect through it transparently.
     *
     * @param connectionKey the connection whose proxy should be recreated
     */
    private void recreateProxy(@NonNull final ConnectionKey connectionKey) {
        final Proxy previous = requireProxy(connectionKey);
        toxiproxyClient.deleteProxy(previous);

        // A proxy carries its upstream (sender -> receiver) traffic and, on its downstream, the reverse connection's
        // (receiver -> sender) traffic, so its toxics are reapplied from both connection states.
        final ConnectionState upstream = connections.getOrDefault(connectionKey, DISCONNECTED);
        final ConnectionState downstream = connections.getOrDefault(connectionKey.reversed(), DISCONNECTED);

        final Proxy proxy = toxiproxyClient.createProxy(
                new Proxy(previous.name(), previous.listen(), previous.upstream(), upstream.connected()));
        toxiproxyClient.createToxin(proxy, new LatencyToxin(upstream.latency(), upstream.jitter()));
        toxiproxyClient.createToxin(proxy, new LatencyToxin(downstream.latency(), downstream.jitter()).downstream());
        toxiproxyClient.createToxin(proxy, new BandwidthToxin(upstream.bandwidthLimit()));
        toxiproxyClient.createToxin(proxy, new BandwidthToxin(downstream.bandwidthLimit()).downstream());
        proxies.put(connectionKey, proxy);
    }

    /**
     * Applies a control-plane mutation to a single proxy, recovering from a wedge. If the mutation fails because the
     * proxy's control plane is wedged ({@link NetworkControlUnavailableException}), the proxy is recreated from scratch
     * &ndash; the only operation that escapes the wedge &ndash; which restores its intended state directly, so the
     * failed mutation needs no separate retry.
     *
     * @param connectionKey the proxy to mutate
     * @param mutation the mutation to attempt
     */
    private void applyToProxy(@NonNull final ConnectionKey connectionKey, @NonNull final Runnable mutation) {
        try {
            mutation.run();
        } catch (final NetworkControlUnavailableException e) {
            log.warn(
                    "Proxy between sender {} and receiver {} is wedged; recreating it to recover",
                    connectionKey.sender(),
                    connectionKey.receiver(),
                    e);
            recreateProxy(connectionKey);
        }
    }

    @NonNull
    private Proxy requireProxy(@NonNull final ConnectionKey connectionKey) {
        final Proxy proxy = proxies.get(connectionKey);
        if (proxy == null) {
            throw new IllegalStateException("No proxy found for sender %s and receiver %s"
                    .formatted(connectionKey.sender(), connectionKey.receiver()));
        }
        return proxy;
    }

    private void connect(@NonNull final ConnectionKey connectionKey) {
        log.debug("Connecting sender {} and receiver {}", connectionKey.sender(), connectionKey.receiver());
        applyToProxy(
                connectionKey,
                () -> proxies.put(
                        connectionKey,
                        toxiproxyClient.updateProxy(requireProxy(connectionKey).withEnabled(true))));
    }

    private void disconnect(@NonNull final ConnectionKey connectionKey) {
        log.debug("Disconnecting sender {} and receiver {}", connectionKey.sender(), connectionKey.receiver());
        applyToProxy(
                connectionKey,
                () -> proxies.put(
                        connectionKey,
                        toxiproxyClient.updateProxy(requireProxy(connectionKey).withEnabled(false))));
    }

    private void setLatency(
            @NonNull final ConnectionKey connectionKey, @NonNull final ConnectionState newConnectionState) {
        log.debug(
                "Setting latency between sender {} and receiver {} to {} (+- {})",
                connectionKey.sender(),
                connectionKey.receiver(),
                newConnectionState.latency(),
                newConnectionState.jitter());
        final LatencyToxin latencyToxin = new LatencyToxin(newConnectionState.latency(), newConnectionState.jitter());
        updateToxin(connectionKey, latencyToxin);
    }

    private void setBandwidthLimit(
            @NonNull final ConnectionKey connectionKey, @NonNull final ConnectionState newConnectionState) {
        log.debug(
                "Setting bandwidth between sender {} and receiver {} to {}",
                connectionKey.sender(),
                connectionKey.receiver(),
                newConnectionState.bandwidthLimit());
        final BandwidthToxin bandwidthToxin = new BandwidthToxin(newConnectionState.bandwidthLimit());
        updateToxin(connectionKey, bandwidthToxin);
    }

    private void updateToxin(@NonNull final ConnectionKey connectionKey, @NonNull final Toxin toxin) {
        updateToxinSingleStream(connectionKey, toxin);
        updateToxinSingleStream(connectionKey.reversed(), toxin.downstream());
    }

    private void updateToxinSingleStream(@NonNull final ConnectionKey connectionKey, @NonNull final Toxin toxin) {
        applyToProxy(connectionKey, () -> toxiproxyClient.updateToxin(requireProxy(connectionKey), toxin));
    }

    /**
     * Gets the {@link NetworkEndpoint} of the proxy for a connection between two nodes.
     *
     * @param sender   the node that sends messages
     * @param receiver the node that receives messages
     * @return the endpoint of the proxy
     * @throws NullPointerException  if either sender or receiver is {@code null}
     * @throws IllegalStateException if the connection cannot be found
     */
    @NonNull
    public NetworkEndpoint getProxyEndpoint(@NonNull final Node sender, @NonNull final Node receiver) {
        final ConnectionKey connectionKey = new ConnectionKey(sender.selfId(), receiver.selfId());
        final Proxy proxy = proxies.get(connectionKey);
        if (proxy == null) {
            throw new IllegalStateException(
                    "No proxy found for sender %s and receiver %s".formatted(sender.selfId(), receiver.selfId()));
        }

        try {
            final URI uri = URI.create("http://" + proxy.listen());
            final InetAddress hostname = InetAddress.getByName(uri.getHost());
            final int port = uri.getPort();
            return new NetworkEndpoint(receiver.selfId().id(), hostname, port);
        } catch (final UnknownHostException e) {
            // this should not happen as the host has just been set up
            throw new UncheckedIOException(e);
        }
    }
}

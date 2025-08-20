// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container.network;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.platform.gossip.config.NetworkEndpoint;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;

/**
 * This class is a wrapper around the Toxiproxy client and provides methods to modify the network behavior.
 */
public class NetworkBehavior {

    private static final Logger log = LogManager.getLogger();

    private final ToxiproxyClient toxiproxyClient;
    private final Map<ConnectionKey, Proxy> proxies = new HashMap<>();

    /**
     * Constructs a new NetworkBehavior instance using the Toxiproxy client.
     *
     * @param host the host on which the Toxiproxy control server is running
     * @param controlPort the port on which the Toxiproxy control server is running
     * @param roster the roster containing the nodes in the network
     * @param ipAddress the IP address of the Toxiproxy container in the Docker network
     */
    public NetworkBehavior(
            @NonNull final String host,
            final int controlPort,
            @NonNull final Roster roster,
            @NonNull final String ipAddress) {
        toxiproxyClient = new ToxiproxyClient(host, controlPort);

        final String listenAddress = ipAddress + ":0";

        final List<NodeId> nodeIds = roster.rosterEntries().stream().map(RosterEntry::nodeId).map(NodeId::new).toList();
        for (final RosterEntry receiverEntry : roster.rosterEntries()) {
            final NodeId receiver = new NodeId(receiverEntry.nodeId());
            final ServiceEndpoint endpoint = receiverEntry.gossipEndpoint().getFirst();
            final String receiverAddress = "%s:%d".formatted(endpoint.domainName(), endpoint.port());
            for (final NodeId sender : nodeIds) {
                if (sender.equals(receiver)) {
                    continue;
                }
                log.info("Creating connection between sender {} and receiver {} at address {}", sender, receiver, receiverAddress);

                final ConnectionKey connectionKey = new ConnectionKey(sender, receiver);
                final String connectionName = "%d-%d".formatted(sender.id(), receiver.id());
                final Proxy proxy = new Proxy(connectionName, listenAddress, receiverAddress, true);
                proxies.put(connectionKey, toxiproxyClient.createProxy(proxy));
            }
        }
    }

    /**
     * Connects two nodes in the network.
     *
     * @param sender the node that sends messages
     * @param receiver the node that receives messages
     * @throws NullPointerException if either sender or receiver is {@code null}
     */
    public void connect(@NonNull final Node sender, @NonNull final Node receiver) {
        log.info("Connecting sender {} and receiver {}", sender, receiver);

        final ConnectionKey connectionKey = new ConnectionKey(sender.selfId(), receiver.selfId());
        final Proxy proxy = proxies.get(connectionKey);
        if (proxy == null) {
            throw new IllegalStateException("No proxy found for sender %s and receiver %s".formatted(sender.selfId(), receiver.selfId()));
        }

        proxies.put(connectionKey, toxiproxyClient.updateProxy(proxy.withEnabled(true)));
    }

    /**
     * Disconnects two nodes in the network.
     *
     * @param sender the node that sends messages
     * @param receiver the node that receives messages
     * @throws NullPointerException if either sender or receiver is {@code null}
     */
    public void disconnect(@NonNull final Node sender, @NonNull final Node receiver) {
        log.info("Disconnecting sender {} and receiver {}", sender, receiver);

        final ConnectionKey connectionKey = new ConnectionKey(sender.selfId(), receiver.selfId());
        final Proxy proxy = proxies.get(connectionKey);
        if (proxy == null) {
            throw new IllegalStateException("No proxy found for sender %s and receiver %s".formatted(sender.selfId(), receiver.selfId()));
        }

        proxies.put(connectionKey, toxiproxyClient.updateProxy(proxy.withEnabled(false)));
    }

    /**
     * Gets the {@link NetworkEndpoint} of the proxy for a connection between two nodes.
     *
     * @param sender the node that sends messages
     * @param receiver the node that receives messages
     * @return the endpoint of the proxy
     * @throws NullPointerException if either sender or receiver is {@code null}
     * @throws IllegalStateException if the connection cannot be found
     */
    @NonNull
    public NetworkEndpoint getProxyEndpoint(@NonNull final Node sender, @NonNull final Node receiver) {
        final ConnectionKey connectionKey = new ConnectionKey(sender.selfId(), receiver.selfId());
        final Proxy proxy = proxies.get(connectionKey);
        if (proxy == null) {
            throw new IllegalStateException("No proxy found for sender %s and receiver %s".formatted(sender.selfId(), receiver.selfId()));
        }

        try {
            final URI uri = URI.create("http://" + proxy.listen());
            final InetAddress hostname = InetAddress.getByName(uri.getHost());
            final int port = uri.getPort();
            return new NetworkEndpoint(
                    receiver.selfId().id(),
                    hostname,
                    port);
        } catch (final UnknownHostException e) {
            // this should not happen as the host has just been set up
            throw new UncheckedIOException(e);
        }
    }
}

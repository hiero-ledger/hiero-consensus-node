// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Caches one long-lived {@link ClprEndpointClient} per peer address so outbound CLPR calls reuse the
 * underlying HTTP/2 connection instead of establishing (and, under TLS, re-handshaking) a fresh one on
 * every sync. At the CLPR sync cadence this avoids a full TCP + TLS handshake per tick and lets HTTP/2
 * multiplexing and keep-alive amortize the connection cost.
 *
 * <p>Clients are keyed by normalized socket address. A cached client is rebuilt when its transport changes
 * (TLS vs. plaintext), and a change to the node's additional endpoint CIDRs retires all cached clients.
 *
 * <p>Thread-safety: {@link #clientFor} and {@link #shutdownAll} serialize changes to the cache and its
 * destination policy. Internally the cache holds each entry as a
 * {@link ClprEndpointClientImpl} so it alone can call the package-private
 * {@link ClprEndpointClientImpl#shutdownChannel()} on a transport change or {@link #shutdownAll()};
 * {@link #clientFor} hands callers back only the {@link ClprEndpointClient} interface, which has no
 * shutdown method at all, so nothing a caller does with the returned client can tear it down out from
 * under other callers.
 */
@Singleton
public class ClprEndpointClientCache {
    private static final Logger logger = LogManager.getLogger(ClprEndpointClientCache.class);

    private final Map<InetSocketAddress, CachedClient> clients = new HashMap<>();
    private final ConfigProvider configProvider;
    private Set<String> appliedCidrs = Set.of();

    @Inject
    public ClprEndpointClientCache(@NonNull final ConfigProvider configProvider) {
        this.configProvider = requireNonNull(configProvider);
    }

    /**
     * Returns the cached client for the given peer, creating it on first use and rebuilding it if the
     * transport (TLS vs. plaintext) has changed since it was built.
     *
     * @param host the peer's IP address
     * @param port the peer's gRPC port
     * @param useTls whether to encrypt the channel with TLS
     * @return the cached client for this peer
     */
    @NonNull
    public synchronized ClprEndpointClient clientFor(@NonNull final String host, final int port, final boolean useTls) {
        requireNonNull(host);
        final var additionalCidrs = configProvider
                .getConfiguration()
                .getConfigData(ClprConfig.class)
                .additionalPeerEndpointCidrs();
        if (!appliedCidrs.equals(additionalCidrs)) {
            // A cached transport can reconnect on its own. Retire it when the node's destination policy changes.
            shutdownAll();
            appliedCidrs = Set.copyOf(additionalCidrs);
        }
        // Check the current node-local policy before returning even an existing cached client.
        final var key = ClprEndpointAddressPolicy.addressFor(host, port, appliedCidrs);
        final var cached = clients.compute(key, (k, existing) -> {
            if (existing != null && existing.useTls() == useTls) {
                return existing;
            }
            if (existing != null) {
                logger.debug("[CLPR] rebuilding CLPR client for peer {} — transport changed (useTls={})", k, useTls);
                existing.client().shutdownChannel();
            }
            return new CachedClient(new ClprEndpointClientImpl(key, useTls), useTls);
        });
        return cached.client();
    }

    /**
     * Shuts down all cached clients' channels and clears the cache. Idempotent; safe to call on node stop.
     */
    public synchronized void shutdownAll() {
        for (final var cached : clients.values()) {
            cached.client().shutdownChannel();
        }
        clients.clear();
    }

    /**
     * A cached client paired with the transport it was built for.
     */
    private record CachedClient(@NonNull ClprEndpointClientImpl client, boolean useTls) {}
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>Clients are keyed by {@code host:port}. A cached client is rebuilt only when the transport to use for
 * that peer changes (TLS vs. plaintext), which happens when the peer starts or stops advertising a TLS
 * certificate in its Endpoint Manifest entry.
 *
 * <p>Thread-safety: {@link #clientFor} builds and swaps clients atomically per key via
 * {@link ConcurrentHashMap#compute}. Internally the cache holds each entry as a
 * {@link ClprEndpointClientImpl} so it alone can call the package-private
 * {@link ClprEndpointClientImpl#shutdownChannel()} on a transport change or {@link #shutdownAll()};
 * {@link #clientFor} hands callers back only the {@link ClprEndpointClient} interface, which has no
 * shutdown method at all, so nothing a caller does with the returned client can tear it down out from
 * under other callers.
 */
@Singleton
public class ClprEndpointClientCache {
    private static final Logger logger = LogManager.getLogger(ClprEndpointClientCache.class);

    private final Map<String, CachedClient> clients = new ConcurrentHashMap<>();

    @Inject
    public ClprEndpointClientCache() {
        // Injected as a singleton; no dependencies.
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
    public ClprEndpointClient clientFor(@NonNull final String host, final int port, final boolean useTls) {
        requireNonNull(host);
        final var key = host + ":" + port;
        final var cached = clients.compute(key, (k, existing) -> {
            if (existing != null && existing.useTls() == useTls) {
                return existing;
            }
            if (existing != null) {
                logger.debug("[CLPR] rebuilding CLPR client for peer {} — transport changed (useTls={})", k, useTls);
                existing.client().shutdownChannel();
            }
            return new CachedClient(new ClprEndpointClientImpl(host, port, useTls), useTls);
        });
        return cached.client();
    }

    /**
     * Shuts down all cached clients' channels and clears the cache. Idempotent; safe to call on node stop.
     */
    public void shutdownAll() {
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

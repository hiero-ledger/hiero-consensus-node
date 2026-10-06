// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Caches one long-lived {@link ClprEndpointClient} per peer address so outbound CLPR calls reuse the
 * underlying HTTP/2 connection instead of establishing (and, under mTLS, re-handshaking) a fresh one on
 * every sync. At the CLPR sync cadence this avoids a full TCP + mTLS handshake per tick and lets HTTP/2
 * multiplexing and keep-alive amortize the connection cost.
 *
 * <p>Clients are keyed by {@code host:port}. A cached client is rebuilt only when the peer's pinned CA
 * certificate changes (the sole per-peer input to its channel's TLS trust anchor); this node's leaf
 * identity is a per-process singleton and never rotates, so it is not part of the key. In plaintext mode
 * the certificate is irrelevant and normalized to {@code null}, so plaintext callers to the same peer
 * share one client.
 *
 * <p>Thread-safety: {@link #clientFor} builds and swaps clients atomically per key via
 * {@link ConcurrentHashMap#compute}. Internally the cache holds each entry as a
 * {@link ClprEndpointClientImpl} so it alone can call the package-private
 * {@link ClprEndpointClientImpl#shutdownChannel()} on cert rotation or {@link #shutdownAll()};
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
     * pinned peer certificate has changed since it was built.
     *
     * @param host the peer's IP address
     * @param port the peer's gRPC port
     * @param peerTlsCertificate the peer's DER-encoded CA cert (mTLS only); ignored in plaintext mode
     * @param clientCredentials this node's leaf identity for mTLS, or {@code null} for plaintext
     * @return the cached client for this peer
     */
    @NonNull
    public ClprEndpointClient clientFor(
            @NonNull final String host,
            final int port,
            @Nullable final Bytes peerTlsCertificate,
            @Nullable final ClprLeafCredentials clientCredentials) {
        requireNonNull(host);
        // In plaintext mode the cert is unused, so normalize it out of the cache discriminator; otherwise a
        // plaintext sync (cert present) and a plaintext discovery (cert null) to the same peer would thrash.
        final Bytes pinnedCert = clientCredentials != null ? peerTlsCertificate : null;
        final var key = host + ":" + port;
        final var cached = clients.compute(key, (k, existing) -> {
            if (existing != null && existing.matches(pinnedCert)) {
                return existing;
            }
            // If a found cert exists but doesn't match the pinned cert, the pinned cert has changed and requires a new
            // client
            if (existing != null) {
                logger.debug("[CLPR] rebuilding CLPR client for peer {} — pinned certificate changed", k);
                existing.client().shutdownChannel();
            }
            return new CachedClient(
                    new ClprEndpointClientImpl(host, port, peerTlsCertificate, clientCredentials), pinnedCert);
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
     * A cached client paired with the pinned peer certificate it was built for. {@code peerCert} is
     * {@code null} for plaintext clients.
     */
    private record CachedClient(
            @NonNull ClprEndpointClientImpl client,
            @Nullable Bytes peerCert) {
        boolean matches(@Nullable final Bytes candidateCert) {
            return Objects.equals(peerCert, candidateCert);
        }
    }
}

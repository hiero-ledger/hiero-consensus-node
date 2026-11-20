// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static com.hedera.node.app.service.clpr.impl.roster.ClprEndpointBuilder.isDnsHostname;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.workflows.clpr.ClprEndpointClientImpl.ClientException;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.Deadline;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * <p>Numeric peers share clients by normalized address. DNS peers, dialed only when
 * {@code clpr.allowDnsPeerEndpoints} is set, keep their client while DNS still returns its address and the
 * transport matches. Changing either destination-policy setting retires all cached clients.
 *
 * <p>DNS lookups run outside the cache lock on a separate thread, bounded by {@code clpr.syncTimeoutSeconds}.
 * Cache mutations are serialized with changes to the destination policy. Internally the cache holds each entry
 * as a {@link ClprEndpointClientImpl} so it alone can call the package-private
 * {@link ClprEndpointClientImpl#retireChannel()} on a replacement or {@link ClprEndpointClientImpl#shutdownChannel()}
 * on a policy change or {@link #shutdownAll()}; {@link #clientFor} hands callers back only the
 * {@link ClprEndpointClient} interface, which has no shutdown method at all, so nothing a caller does with the
 * returned client can tear it down out from under other callers.
 */
@Singleton
public class ClprEndpointClientCache implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(ClprEndpointClientCache.class);

    private final Map<InetSocketAddress, CachedClient> clients = new HashMap<>();
    private final ConfigProvider configProvider;
    private final HostResolver resolver;
    private final ExecutorService dnsLookups;
    private Set<String> appliedCidrs = Set.of();

    @Nullable
    private Policy appliedPolicy;

    @Inject
    public ClprEndpointClientCache(@NonNull final ConfigProvider configProvider) {
        this(configProvider, InetAddress::getAllByName);
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    ClprEndpointClientCache(@NonNull final ConfigProvider configProvider, @NonNull final HostResolver resolver) {
        this.configProvider = requireNonNull(configProvider);
        this.resolver = requireNonNull(resolver);
        // Leave a sync worker available for numeric peers when possible. A native DNS lookup can outlive
        // cancellation, so bound the number of resolver threads and never queue work behind a stalled lookup.
        final int syncWorkers = configProvider
                .getConfiguration()
                .getConfigData(ClprConfig.class)
                .maxConcurrentSyncs();
        dnsLookups = new ThreadPoolExecutor(
                0,
                Math.max(1, syncWorkers - 1),
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                Thread.ofPlatform().daemon().name("clpr-dns-", 0).factory());
    }

    /**
     * Returns the cached client for the given peer, creating it on first use and rebuilding it if the
     * transport (TLS vs. plaintext) or a DNS peer's address has changed since it was built.
     *
     * @param host the peer's IP address or DNS name
     * @param port the peer's gRPC port
     * @param useTls whether to encrypt the channel with TLS
     * @param deadline deadline of the sync requesting the client
     * @return the cached client for this peer
     * @throws RejectedExecutionException when the local DNS resolver cannot accept another lookup
     */
    @NonNull
    public ClprEndpointClient clientFor(
            @NonNull final String host, final int port, final boolean useTls, @NonNull final Deadline deadline) {
        requireNonNull(host);
        requireNonNull(deadline);
        final Policy policy;
        final InetSocketAddress key;
        final CachedClient previous;
        final boolean dns = isDnsHostname(host);
        synchronized (this) {
            policy = applyPolicy();
            checkDeadline(deadline);
            if (dns) {
                requireDnsAllowed(policy, host, port);
                ClprEndpointAddressPolicy.checkPort(host, port);
                key = InetSocketAddress.createUnresolved(NodeIdentity.dnsKey(host), port);
            } else {
                key = policy.addresses().addressFor(host, port);
            }
            previous = clients.get(key);
        }

        final List<InetSocketAddress> candidates;
        if (dns) {
            try {
                candidates = permittedAnswers(port, policy, resolve(host, deadline));
            } catch (final UnknownHostException e) {
                evict(key, previous);
                throw new ClientException("Cannot resolve CLPR peer endpoint " + host, e);
            }
            if (candidates.isEmpty()) {
                evict(key, previous);
                throw new ClientException("CLPR peer endpoint " + host + " has no permitted DNS answers", null);
            }
        } else {
            candidates = List.of(key);
        }

        final CachedClient replaced;
        final ClprEndpointClientImpl client;
        synchronized (this) {
            // A stop, clear or policy change during DNS invalidates the lookup, even if the resolver ignored
            // interruption.
            if (applyPolicy() != policy) {
                throw new ClientException("CLPR endpoint policy changed during lookup of " + host, null);
            }
            checkDeadline(deadline);
            replaced = clients.get(key);
            final var address = replaced != null && candidates.contains(replaced.address())
                    ? replaced.address()
                    : candidates.getFirst();
            if (replaced != null && replaced.address().equals(address) && replaced.useTls() == useTls) {
                return replaced.client();
            }
            client = new ClprEndpointClientImpl(address, useTls);
            clients.put(key, new CachedClient(client, address, useTls));
        }
        if (replaced != null) {
            logger.debug("[CLPR] rebuilding CLPR client for peer {} (useTls={})", key, useTls);
            replaced.client().retireChannel();
        }
        return client;
    }

    /** Invalidates outstanding lookups and closes every cached channel. */
    public synchronized void shutdownAll() {
        appliedPolicy = null;
        for (final var cached : clients.values()) {
            cached.client().shutdownChannel();
        }
        clients.clear();
    }

    /** Stops the resolver and prevents any subsequent sync from installing a channel. */
    @Override
    public synchronized void close() {
        dnsLookups.shutdownNow();
        shutdownAll();
    }

    /** Called under the cache lock. A change in either destination setting invalidates all cached transports. */
    private Policy applyPolicy() {
        if (dnsLookups.isShutdown()) {
            throw new ClientException("CLPR endpoint client cache is closed", null);
        }
        final var config = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        final var cidrs = config.additionalPeerEndpointCidrs();
        if (appliedPolicy == null
                || appliedPolicy.allowDns() != config.allowDnsPeerEndpoints()
                || !appliedCidrs.equals(cidrs)) {
            shutdownAll();
            appliedCidrs = Set.copyOf(cidrs);
            appliedPolicy = new Policy(config.allowDnsPeerEndpoints(), ClprEndpointAddressPolicy.of(appliedCidrs));
        }
        return appliedPolicy;
    }

    private InetAddress[] resolve(final String host, final Deadline deadline) throws UnknownHostException {
        final var lookup = dnsLookups.submit(() -> resolver.resolve(host));
        try {
            return lookup.get(deadline.timeRemaining(TimeUnit.NANOSECONDS), TimeUnit.NANOSECONDS);
        } catch (final ExecutionException e) {
            if (e.getCause() instanceof UnknownHostException unknown) {
                throw unknown;
            }
            throw new ClientException("Cannot resolve CLPR peer endpoint " + host, e.getCause());
        } catch (final TimeoutException e) {
            lookup.cancel(true);
            throw new ClientException("Timed out resolving CLPR peer endpoint " + host, e);
        } catch (final InterruptedException e) {
            lookup.cancel(true);
            Thread.currentThread().interrupt();
            throw new ClientException("Interrupted resolving CLPR peer endpoint " + host, e);
        }
    }

    private static List<InetSocketAddress> permittedAnswers(
            final int port, final Policy policy, final InetAddress[] answers) {
        final List<InetSocketAddress> candidates = new ArrayList<>();
        for (final var answer : answers) {
            final var candidate = policy.addresses().permittedAddress(answer, port);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private static void checkDeadline(final Deadline deadline) {
        if (deadline.isExpired() || Thread.currentThread().isInterrupted()) {
            throw new ClientException("CLPR sync expired or interrupted during peer lookup", null);
        }
    }

    /** Only evict the client observed before lookup. A concurrent sync may already have installed a replacement. */
    private void evict(final InetSocketAddress key, @Nullable final CachedClient previous) {
        synchronized (this) {
            if (previous == null || !clients.remove(key, previous)) {
                return;
            }
        }
        previous.client().retireChannel();
    }

    private static void requireDnsAllowed(final Policy policy, final String host, final int port) {
        if (!policy.allowDns()) {
            throw new ClientException(
                    "CLPR peer endpoint " + host + ":" + port
                            + " is a DNS name; set clpr.allowDnsPeerEndpoints=true on this node to dial it",
                    null);
        }
    }

    /** This node's destination policy: whether DNS peers may be dialed, and the permitted address ranges. */
    private record Policy(boolean allowDns, @NonNull ClprEndpointAddressPolicy addresses) {}

    /**
     * A cached client paired with the address and transport it was built for.
     */
    private record CachedClient(
            @NonNull ClprEndpointClientImpl client, @NonNull InetSocketAddress address, boolean useTls) {}
}

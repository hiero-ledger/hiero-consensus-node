// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.google.common.net.InetAddresses;
import com.google.common.net.InternetDomainName;
import com.hedera.node.app.workflows.clpr.ClprEndpointClientImpl.ClientException;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
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
 * <p>Numeric peers share clients by normalized address. DNS peers reuse a client while their resolved IP
 * and transport match. Changing either destination-policy setting retires all cached clients.
 *
 * <p>DNS lookups run outside the cache lock. Cache mutations are serialized with changes to the
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
    private final HostResolver resolver;
    private boolean appliedRequireNumeric = true;
    private Set<String> appliedCidrs = Set.of();

    @Inject
    public ClprEndpointClientCache(@NonNull final ConfigProvider configProvider) {
        this(configProvider, InetAddress::getByName);
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress resolve(String host) throws UnknownHostException;
    }

    ClprEndpointClientCache(@NonNull final ConfigProvider configProvider, @NonNull final HostResolver resolver) {
        this.configProvider = requireNonNull(configProvider);
        this.resolver = requireNonNull(resolver);
    }

    /**
     * Returns the cached client for the given peer, creating it on first use and rebuilding it if the
     * transport (TLS vs. plaintext) has changed since it was built.
     *
     * @param host the peer's IP address or DNS name
     * @param port the peer's gRPC port
     * @param useTls whether to encrypt the channel with TLS
     * @return the cached client for this peer
     */
    @NonNull
    public ClprEndpointClient clientFor(@NonNull final String host, final int port, final boolean useTls) {
        requireNonNull(host);
        // Only DNS names take this path. Numeric inputs and CIDR entries always use the literal parser.
        if (port > 0 && port <= 65535 && InternetDomainName.isValid(host) && !currentPolicyRequiresNumeric()) {
            try {
                return checkedClientFor(host, port, useTls, resolver.resolve(host));
            } catch (final UnknownHostException e) {
                throw new ClientException("Cannot resolve CLPR peer endpoint " + host, e);
            }
        }
        return checkedClientFor(host, port, useTls, null);
    }

    private synchronized boolean currentPolicyRequiresNumeric() {
        final var config = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        if (appliedRequireNumeric != config.requireNumericPeerEndpoints()
                || !appliedCidrs.equals(config.additionalPeerEndpointCidrs())) {
            shutdownAll();
            appliedRequireNumeric = config.requireNumericPeerEndpoints();
            appliedCidrs = Set.copyOf(config.additionalPeerEndpointCidrs());
        }
        return appliedRequireNumeric;
    }

    private synchronized ClprEndpointClient checkedClientFor(
            final String host, final int port, final boolean useTls, @Nullable final InetAddress dnsAnswer) {
        // Recheck the policy after resolution. Only the validated numeric address ever reaches gRPC.
        final boolean requireNumeric = currentPolicyRequiresNumeric();
        final boolean useDnsAnswer = dnsAnswer != null && !requireNumeric;
        final var address = ClprEndpointAddressPolicy.addressFor(
                useDnsAnswer ? InetAddresses.toAddrString(dnsAnswer) : host, port, appliedCidrs);
        final var key = useDnsAnswer ? InetSocketAddress.createUnresolved(host, port) : address;
        final var cached = clients.get(key);
        if (cached != null && cached.address().equals(address) && cached.useTls() == useTls) {
            return cached.client();
        }
        final var client = new ClprEndpointClientImpl(address, useTls);
        if (cached != null) {
            logger.debug("[CLPR] rebuilding CLPR client for peer {} (address={}, useTls={})", key, address, useTls);
            cached.client().shutdownChannel();
        }
        clients.put(key, new CachedClient(client, address, useTls));
        return client;
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
    private record CachedClient(
            @NonNull ClprEndpointClientImpl client, @NonNull InetSocketAddress address, boolean useTls) {}
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static java.util.Objects.requireNonNull;

import com.google.common.base.CharMatcher;
import com.google.common.net.InetAddresses;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.node.app.service.clpr.impl.roster.ClprEndpointBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/** Bounds peer-controlled manifest state before it is stored or seeded into the endpoint cache. */
public final class ClprPeerManifestPolicy {
    /** Maximum serialized bytes retained per channel, including certificates and manifest framing. */
    public static final int MAX_STORED_MANIFEST_BYTES = 16_384;
    /** Maximum characters in a peer chain id. */
    public static final int MAX_CHAIN_ID_LENGTH = 128;

    /** Visible characters: basic Latin letters, digits and punctuation, without spaces or control characters. */
    private static final CharMatcher VISIBLE_CHARACTERS = CharMatcher.inRange('!', '~');

    private ClprPeerManifestPolicy() {}

    /**
     * Keeps the declared prefix that fits both the local endpoint-count policy and the storage-byte ceiling.
     * A zero count limit removes only the count restriction; the byte ceiling always applies.
     *
     * @param manifest the authenticated peer manifest
     * @param maxPeerEndpoints the local count limit, or zero for no count restriction
     * @return the bounded manifest, retaining its authenticated version and service address
     * @throws IllegalArgumentException if the manifest framing alone exceeds the storage ceiling, or a retained
     *     endpoint lacks a service endpoint or carries a host that is neither a numeric IP address nor a DNS hostname
     */
    @NonNull
    public static ClprEndpointManifest boundedManifest(
            @NonNull final ClprEndpointManifest manifest, final int maxPeerEndpoints) {
        requireNonNull(manifest);
        final var empty = manifest.copyBuilder().endpoints(List.of()).build();
        long storedBytes = ClprEndpointManifest.PROTOBUF.measureRecord(empty);
        if (storedBytes > MAX_STORED_MANIFEST_BYTES) {
            throw new IllegalArgumentException("Peer manifest framing exceeds storage limit");
        }
        final var endpoints = manifest.endpoints();
        final int countLimit = maxPeerEndpoints > 0 ? Math.min(maxPeerEndpoints, endpoints.size()) : endpoints.size();
        int retained = 0;
        while (retained < countLimit) {
            final int endpointBytes = ClprEndpoint.PROTOBUF.measureRecord(endpoints.get(retained));
            // Field 3 uses one tag byte followed by a varint length and the endpoint message.
            final long framedBytes = 1L + varintSize(endpointBytes) + endpointBytes;
            if (storedBytes + framedBytes > MAX_STORED_MANIFEST_BYTES) {
                break;
            }
            storedBytes += framedBytes;
            retained++;
        }
        for (int i = 0; i < retained; i++) {
            final var serviceEndpoint = endpoints.get(i).serviceEndpoint();
            if (serviceEndpoint == null || !isValidHost(serviceEndpoint.ipAddress())) {
                throw new IllegalArgumentException("Peer manifest endpoint host is not an IP address or DNS hostname");
            }
        }
        return retained == endpoints.size()
                ? manifest
                : manifest.copyBuilder()
                        .endpoints(List.copyOf(endpoints.subList(0, retained)))
                        .build();
    }

    /**
     * Whether a peer endpoint host may be stored: a numeric IPv4 or IPv6 address without a scope, or a DNS hostname.
     * Rejects empty hosts, which cannot be dialed, and control characters such as NUL, which downstream consumers of
     * the block stream cannot store.
     *
     * @param host the peer-supplied host
     * @return whether the host is well formed
     */
    public static boolean isValidHost(@NonNull final String host) {
        requireNonNull(host);
        if (host.isEmpty() || !VISIBLE_CHARACTERS.matchesAllOf(host) || host.indexOf('%') >= 0) {
            return false;
        }
        return InetAddresses.isInetAddress(host) || ClprEndpointBuilder.isDnsHostname(host);
    }

    /**
     * Whether a peer chain id may be stored: non-empty, at most {@value #MAX_CHAIN_ID_LENGTH} characters, and
     * visible characters only.
     *
     * @param chainId the peer-supplied chain id
     * @return whether the chain id is well formed
     */
    public static boolean isValidChainId(@NonNull final String chainId) {
        requireNonNull(chainId);
        return !chainId.isEmpty()
                && chainId.length() <= MAX_CHAIN_ID_LENGTH
                && VISIBLE_CHARACTERS.matchesAllOf(chainId);
    }

    private static int varintSize(int value) {
        int size = 1;
        while ((value >>>= 7) != 0) {
            size++;
        }
        return size;
    }
}

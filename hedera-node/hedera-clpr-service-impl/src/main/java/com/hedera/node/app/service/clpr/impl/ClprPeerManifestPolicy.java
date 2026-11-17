// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/** Bounds peer-controlled manifest state before it is stored or seeded into the endpoint cache. */
public final class ClprPeerManifestPolicy {
    /** Maximum serialized bytes retained per channel, including certificates and manifest framing. */
    public static final int MAX_STORED_MANIFEST_BYTES = 16_384;

    private ClprPeerManifestPolicy() {}

    /**
     * Keeps the declared prefix that fits both the local endpoint-count policy and the storage-byte ceiling.
     * A zero count limit removes only the count restriction; the byte ceiling always applies.
     *
     * @param manifest the authenticated peer manifest
     * @param maxPeerEndpoints the local count limit, or zero for no count restriction
     * @return the bounded manifest, retaining its authenticated version and service address
     * @throws IllegalArgumentException if the manifest framing alone exceeds the storage ceiling
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
        return retained == endpoints.size()
                ? manifest
                : manifest.copyBuilder()
                        .endpoints(List.copyOf(endpoints.subList(0, retained)))
                        .build();
    }

    private static int varintSize(int value) {
        int size = 1;
        while ((value >>>= 7) != 0) {
            size++;
        }
        return size;
    }
}

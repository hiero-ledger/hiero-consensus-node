// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.roster;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.charset.StandardCharsets;

/**
 * Builds a single node's {@link ClprEndpoint} from a mix of the platform's authoritative
 * address book (the node's IP) and this node's <em>local</em> CLPR mTLS configuration (the
 * sync port and CA certificate). Used by the reconciler's self-publication path
 * ({@code openConstructionIfSelfChanged} / {@code contributeSelfToConstruction}): each node derives
 * <b>its own</b> CLPR endpoint and publishes it via {@code ClprEndpointPublication}.
 *
 * <p><b>Self-only.</b> Because the mTLS port ({@code clpr.mtlsPort}) and the CA certificate
 * ({@code clpr.caCrtPath}) are node-local — not carried in the consensus address book — this
 * builder can only produce a faithful endpoint for the running node, whose {@code mtlsPort}
 * and {@code selfCaCertDer} the caller supplies. Peers' endpoints arrive via their own
 * publications, not through this builder.
 *
 * <p>Fields (aligned with the mTLS two-tier cert model, spec §4.4 / PR #316):
 * <ul>
 *   <li>{@code service_endpoint.ip_address} = the node's first address-book service endpoint
 *       (IPv4 literal or domain name).</li>
 *   <li>{@code service_endpoint.port} — when mTLS is enabled, {@code clpr.mtlsPort} (the
 *       dedicated mutual-auth CLPR sync listener); when mTLS is <b>not</b> configured, the node
 *       falls back to its address-book HAPI gRPC port, on which plaintext {@code sync} still
 *       runs (mtls.md "Listener topology").</li>
 *   <li>{@code tls_certificate} = the DER-encoded ECDSA P-384 <b>CA</b> certificate peers pin as
 *       the trust anchor when mTLS is enabled, or empty when mTLS is not configured (dev/test) —
 *       an empty cert is a valid endpoint and signals the plaintext path.</li>
 *   <li>{@code account_id} = the node operator account, serialized via
 *       {@link AccountID#PROTOBUF}.</li>
 * </ul>
 */
public final class ClprEndpointBuilder {

    private ClprEndpointBuilder() {}

    /**
     * Build the {@link ClprEndpoint} for the given (self) node, or return {@code null} if the
     * node is missing / deleted / lacks an address-book service endpoint. A {@code null} result
     * means the reconciler should not attempt to publish for this node.
     *
     * @param nodeId the node whose endpoint to build (expected to be the running node)
     * @param nodeStore address book (source of the node's IP and HAPI-port fallback)
     * @param mtlsEnabled whether this node has CLPR mTLS configured ({@code clpr.caCrtPath} set).
     *     When {@code false} the endpoint falls back to plaintext: the HAPI gRPC port and an empty
     *     certificate.
     * @param mtlsPort the {@code clpr.mtlsPort} to advertise as the CLPR sync port when
     *     {@code mtlsEnabled}
     * @param selfCaCertDer the DER-encoded ECDSA CA certificate to advertise when
     *     {@code mtlsEnabled}; ignored (empty is published) otherwise
     */
    @Nullable
    public static ClprEndpoint buildFor(
            final long nodeId,
            @NonNull final ReadableNodeStore nodeStore,
            final boolean mtlsEnabled,
            final int mtlsPort,
            @NonNull final Bytes selfCaCertDer) {
        requireNonNull(nodeStore);
        requireNonNull(selfCaCertDer);
        final var node = nodeStore.get(nodeId);
        if (node == null || node.deleted() || node.serviceEndpoint().isEmpty()) {
            return null;
        }
        return buildEndpoint(node, mtlsEnabled, mtlsPort, selfCaCertDer);
    }

    private static ClprEndpoint buildEndpoint(
            @NonNull final Node node,
            final boolean mtlsEnabled,
            final int mtlsPort,
            @NonNull final Bytes selfCaCertDer) {
        final ServiceEndpoint hapiEndpoint = node.serviceEndpoint().getFirst();
        // mTLS on: advertise the dedicated mutual-auth sync port + CA cert. mTLS off: plaintext
        // sync stays on the HAPI gRPC port and no certificate is advertised.
        final int port = mtlsEnabled ? mtlsPort : hapiEndpoint.port();
        final Bytes tlsCertificate = mtlsEnabled ? selfCaCertDer : Bytes.EMPTY;
        return ClprEndpoint.newBuilder()
                .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                        .ipAddress(ipOf(hapiEndpoint))
                        .port(port)
                        .build())
                .tlsCertificate(tlsCertificate)
                .accountId(AccountID.PROTOBUF.toBytes(node.accountIdOrElse(AccountID.DEFAULT)))
                .build();
    }

    /**
     * The IP address string this builder would advertise for {@code nodeId}, derived from the
     * address book exactly as {@link #buildFor} does, or {@code null} if the node is missing /
     * deleted / has no service endpoint. This is the only part of a peer's endpoint that is
     * derivable from the shared address book (the mTLS port and CA cert are node-local), so the
     * reconciler uses it as the address-book-derivable identity for its open-construction trigger.
     */
    @Nullable
    public static String ipAddressOf(final long nodeId, @NonNull final ReadableNodeStore nodeStore) {
        requireNonNull(nodeStore);
        final var node = nodeStore.get(nodeId);
        if (node == null || node.deleted() || node.serviceEndpoint().isEmpty()) {
            return null;
        }
        return ipOf(node.serviceEndpoint().getFirst());
    }

    private static String ipOf(@NonNull final ServiceEndpoint hapi) {
        return hapi.ipAddressV4().length() == 4 ? formatIpv4(hapi.ipAddressV4()) : hapi.domainName();
    }

    /**
     * Ledger-neutral identity of an endpoint: the IP address when present, otherwise the TLS
     * certificate. Used for deterministic sorting of manifest entries — <b>not</b> for change
     * detection (that must compare the full endpoint, since a cert- or port-only change keeps the
     * same identity). Account-id-free.
     */
    @NonNull
    public static Bytes identityOf(@NonNull final ClprEndpoint endpoint) {
        final var svc = endpoint.serviceEndpoint();
        if (svc != null && svc.ipAddress() != null && !svc.ipAddress().isEmpty()) {
            return Bytes.wrap(svc.ipAddress().getBytes(StandardCharsets.UTF_8));
        }
        return endpoint.tlsCertificate();
    }

    private static String formatIpv4(@NonNull final Bytes ipv4) {
        final byte[] bytes = ipv4.toByteArray();
        return (bytes[0] & 0xff) + "." + (bytes[1] & 0xff) + "." + (bytes[2] & 0xff) + "." + (bytes[3] & 0xff);
    }
}

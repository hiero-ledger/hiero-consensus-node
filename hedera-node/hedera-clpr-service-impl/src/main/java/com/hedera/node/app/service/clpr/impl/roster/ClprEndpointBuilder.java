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
 * address book (the node's IP) and this node's <em>local</em> HAPI TLS configuration (the
 * HAPI TLS port and certificate). Used by the reconciler's self-publication path
 * ({@code openConstructionIfSelfChanged} / {@code contributeSelfToConstruction}): each node derives
 * <b>its own</b> CLPR endpoint and publishes it via {@code ClprEndpointPublication}.
 *
 * <p><b>Self-only.</b> Because the HAPI TLS port ({@code grpc.tlsPort}) and certificate
 * ({@code netty.tlsCrtPath}) are node-local — not carried in the consensus address book — this
 * builder can only produce a faithful endpoint for the running node, whose {@code tlsPort}
 * and {@code tlsCertificateDer} the caller supplies. Peers' endpoints arrive via their own
 * publications, not through this builder.
 *
 * <p>CLPR {@code sync} is served on the HAPI ports. Fields:
 * <ul>
 *   <li>{@code service_endpoint.ip_address} = the node's first address-book service endpoint
 *       (IPv4 literal or domain name).</li>
 *   <li>{@code service_endpoint.port} — when the node has a HAPI TLS certificate, {@code grpc.tlsPort}
 *       (the HAPI TLS listener); otherwise the node falls back to its address-book HAPI gRPC port, on
 *       which plaintext {@code sync} runs.</li>
 *   <li>{@code tls_certificate} = the DER-encoded HAPI TLS certificate, or empty when the node has none
 *       (dev/test). Peers use it only as the signal to dial over TLS; they do not verify it. An empty
 *       cert is a valid endpoint and signals the plaintext path.</li>
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
     * @param tlsPort the {@code grpc.tlsPort} to advertise as the CLPR sync port when
     *     {@code tlsCertificateDer} is non-empty
     * @param tlsCertificateDer the DER-encoded HAPI TLS certificate to advertise, or empty when this
     *     node has none; the endpoint then falls back to plaintext on the address-book HAPI gRPC port
     */
    @Nullable
    public static ClprEndpoint buildFor(
            final long nodeId,
            @NonNull final ReadableNodeStore nodeStore,
            final int tlsPort,
            @NonNull final Bytes tlsCertificateDer) {
        requireNonNull(nodeStore);
        requireNonNull(tlsCertificateDer);
        final var node = nodeStore.get(nodeId);
        if (node == null || node.deleted() || node.serviceEndpoint().isEmpty()) {
            return null;
        }
        return buildEndpoint(node, tlsPort, tlsCertificateDer);
    }

    private static ClprEndpoint buildEndpoint(
            @NonNull final Node node, final int tlsPort, @NonNull final Bytes tlsCertificateDer) {
        final ServiceEndpoint hapiEndpoint = node.serviceEndpoint().getFirst();
        // HAPI TLS certificate present: advertise the HAPI TLS port. Absent: plaintext sync on the
        // address-book HAPI gRPC port.
        final int port = tlsCertificateDer.length() > 0 ? tlsPort : hapiEndpoint.port();
        return ClprEndpoint.newBuilder()
                .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                        .ipAddress(ipOf(hapiEndpoint))
                        .port(port)
                        .build())
                .tlsCertificate(tlsCertificateDer)
                .accountId(AccountID.PROTOBUF.toBytes(node.accountIdOrElse(AccountID.DEFAULT)))
                .build();
    }

    /**
     * The IP address string this builder would advertise for {@code nodeId}, derived from the
     * address book exactly as {@link #buildFor} does, or {@code null} if the node is missing /
     * deleted / has no service endpoint. This is the only part of a peer's endpoint that is
     * derivable from the shared address book (the HAPI TLS port and certificate are node-local), so the
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

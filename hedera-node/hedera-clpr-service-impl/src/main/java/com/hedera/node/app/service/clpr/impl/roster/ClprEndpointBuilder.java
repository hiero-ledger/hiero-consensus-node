// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.roster;

import static com.hedera.hapi.util.HapiUtils.asReadableIp;
import static java.util.Objects.requireNonNull;

import com.google.common.base.CharMatcher;
import com.google.common.net.InternetDomainName;
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
 * address book (the node's IP address or, when {@code clpr.publishDnsEndpoints} is set, its domain name) and
 * this node's <em>local</em> HAPI TLS configuration (the
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
 *   <li>{@code service_endpoint.ip_address} holds the host: the first eligible HAPI endpoint in address-book
 *       order. A domain name is eligible only when {@code clpr.publishDnsEndpoints} is set.</li>
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
     * node is missing / deleted / lacks an eligible HAPI service endpoint. A {@code null} result
     * means the reconciler should not attempt to publish for this node.
     *
     * @param nodeId the node whose endpoint to build (expected to be the running node)
     * @param nodeStore address book (source of the node's host and HAPI-port fallback)
     * @param tlsPort the {@code grpc.tlsPort} to advertise as the CLPR sync port when
     *     {@code tlsCertificateDer} is non-empty
     * @param tlsCertificateDer the DER-encoded HAPI TLS certificate to advertise, or empty when this
     *     node has none; the endpoint then falls back to plaintext on the address-book HAPI gRPC port
     * @param publishDnsEndpoints whether a domain-name endpoint is eligible
     */
    @Nullable
    public static ClprEndpoint buildFor(
            final long nodeId,
            @NonNull final ReadableNodeStore nodeStore,
            final int tlsPort,
            @NonNull final Bytes tlsCertificateDer,
            final boolean publishDnsEndpoints) {
        requireNonNull(nodeStore);
        requireNonNull(tlsCertificateDer);
        final var node = nodeStore.get(nodeId);
        if (node == null || node.deleted()) {
            return null;
        }
        final var hapiEndpoint = firstEndpoint(node, publishDnsEndpoints);
        if (hapiEndpoint == null) {
            return null;
        }
        // HAPI TLS certificate present: advertise the HAPI TLS port. Absent: plaintext sync on the
        // address-book HAPI gRPC port.
        final int port = tlsCertificateDer.length() > 0 ? tlsPort : hapiEndpoint.port();
        return ClprEndpoint.newBuilder()
                .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                        .ipAddress(hostOf(hapiEndpoint))
                        .port(port)
                        .build())
                .tlsCertificate(tlsCertificateDer)
                .accountId(AccountID.PROTOBUF.toBytes(node.accountIdOrElse(AccountID.DEFAULT)))
                .build();
    }

    /**
     * The host string this builder would advertise for {@code nodeId}, derived from the
     * address book exactly as {@link #buildFor} does, or {@code null} if the node is missing /
     * deleted / has no eligible HAPI service endpoint. This is the only part of a peer's endpoint that is
     * derivable from the shared address book (the HAPI TLS port and certificate are node-local), so the
     * reconciler uses it as the address-book-derivable identity for its open-construction trigger.
     */
    @Nullable
    public static String hostOf(
            final long nodeId, @NonNull final ReadableNodeStore nodeStore, final boolean publishDnsEndpoints) {
        requireNonNull(nodeStore);
        final var node = nodeStore.get(nodeId);
        if (node == null || node.deleted()) {
            return null;
        }
        final var hapiEndpoint = firstEndpoint(node, publishDnsEndpoints);
        return hapiEndpoint == null ? null : hostOf(hapiEndpoint);
    }

    @Nullable
    private static ServiceEndpoint firstEndpoint(@NonNull final Node node, final boolean publishDnsEndpoints) {
        for (final var endpoint : node.serviceEndpoint()) {
            if (endpoint.ipAddressV4().length() == 4 || (publishDnsEndpoints && isDnsHostname(endpoint.domainName()))) {
                return endpoint;
            }
        }
        return null;
    }

    /** Shared syntax check for address-book publication and outbound DNS lookup. */
    public static boolean isDnsHostname(@NonNull final String host) {
        return host.length() <= 253
                && CharMatcher.ascii().matchesAllOf(host)
                && host.indexOf('_') < 0
                && InternetDomainName.isValid(host);
    }

    private static String hostOf(final ServiceEndpoint endpoint) {
        return endpoint.ipAddressV4().length() == 4 ? asReadableIp(endpoint.ipAddressV4()) : endpoint.domainName();
    }

    /**
     * Ledger-neutral identity of an endpoint: the host (IP address or domain name) when present, otherwise the TLS
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
}

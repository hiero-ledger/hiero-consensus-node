// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_CHANNEL_STATUS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_PEER_ENDPOINT_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.node.app.service.clpr.impl.WritableChannelStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handler for {@link HederaFunctionality#CLPR_EVICT_PEER_ENDPOINT} transactions.
 *
 * <p>Removes every entry matching the given service endpoint from a Channel's cached peer endpoint manifest, so no node
 * dials it, and refreshes the node-local dial cache to match. This is the admin path for an endpoint a Channel's
 * verifier supplied that turns out to be malicious or unreachable. The manifest version is unchanged, so a later bundle
 * that advances the peer's manifest replaces the cached one and may list the endpoint again; an operator can also deny
 * it locally with {@code clpr.deniedPeerEndpoints}.
 *
 * <p>Requires the CLPR admin key (network admin / superuser), enforced by {@code PrivilegesVerifier.checkClprAdmin}.
 */
@Singleton
public final class ClprEvictPeerEndpointHandler extends AbstractClprHandler {

    private final ClprChannelLifecycle channelLifecycle;

    @Inject
    public ClprEvictPeerEndpointHandler(@NonNull final ClprChannelLifecycle channelLifecycle) {
        this.channelLifecycle = requireNonNull(channelLifecycle);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().clprEvictPeerEndpointOrThrow();
        validateTruePreCheck(op.channelId().length() == CHANNEL_ID_LENGTH, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.hasServiceEndpoint(), INVALID_TRANSACTION_BODY);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        // Authorization enforced by PrivilegesVerifier.checkClprAdmin — only
        // treasury and system admin accounts are permitted.
    }

    @Override
    protected void doHandle(@NonNull final HandleContext context) throws HandleException {
        final var op = context.body().clprEvictPeerEndpointOrThrow();
        final var channelStore = context.storeFactory().writableStore(WritableChannelStore.class);
        final var channel = requireChannel(channelStore, op.channelId());
        validateTrue(channel.status() != ClprChannelStatus.CLOSED, CLPR_INVALID_CHANNEL_STATUS);

        final var manifest = channel.endpointManifestOrElse(ClprEndpointManifest.DEFAULT);
        final var evicted = op.serviceEndpointOrThrow();
        final var remaining = manifest.endpoints().stream()
                .filter(endpoint -> !evicted.equals(endpoint.serviceEndpoint()))
                .toList();
        validateTrue(remaining.size() < manifest.endpoints().size(), CLPR_PEER_ENDPOINT_NOT_FOUND);

        channelStore.put(channel.copyBuilder()
                .endpointManifest(manifest.copyBuilder().endpoints(remaining).build())
                .build());
        channelLifecycle.seedPeerEndpoints(op.channelId(), remaining);
    }
}

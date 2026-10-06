// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import com.hedera.hapi.node.state.clpr.ClprEndpointPublication;
import com.hedera.hapi.node.state.clpr.ClprEndpointPublicationEntry;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.services.auxiliary.clpr.ClprEndpointPublicationTransactionBody;
import com.hedera.node.app.service.clpr.ReadableEndpointManifestStore;
import com.hedera.node.app.service.clpr.impl.WritableEndpointManifestConstructionStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.data.ClprConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.roster.ReadableRosterStore;

/**
 * Handler for {@link ClprEndpointPublicationTransactionBody}. A consensus node self-publishes
 * its CLPR endpoint into the active manifest construction;
 * the assembled manifest is then written to state on construction close. See
 * {@code dev-context/clpr-endpoint-self-publication-design.md} §6.
 *
 * <p><b>Publisher identity.</b> The submitting node is taken from
 * {@code context.creatorInfo().nodeId()} — the platform-authoritative attribution — not
 * from any field on the transaction body. A node cannot publish on another node's behalf.
 *
 * <p><b>Store-write rules.</b> Publications routed into the active construction succeed
 * silently; publications that fail admission (no active construction, publisher outside
 * the target set) are dropped with an info log — not an error, since a subsequent
 * construction will absorb the retry (design §6, §7.1).
 */
@Singleton
public class ClprEndpointPublicationHandler extends AbstractClprHandler {
    private static final Logger log = LogManager.getLogger(ClprEndpointPublicationHandler.class);

    @Inject
    public ClprEndpointPublicationHandler() {}

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().clprEndpointPublicationOrThrow();
        // Every target must publish its full endpoint: construction close does not carry entries over from the
        // previous manifest, so a "no-change" acknowledgment would leave the node out of the manifest.
        validateTruePreCheck(op.hasEndpoint(), INVALID_TRANSACTION_BODY);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        // No additional signers beyond the submitting node's own key (already required as payer).
    }

    @Override
    protected void doHandle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var op = context.body().clprEndpointPublicationOrThrow();
        final long publisherNodeId = context.creatorInfo().nodeId();

        // Master feature-flag guard. When the endpoint-manifest lifecycle is disabled, do not
        // admit or open anything — drop with an info log.
        final var clprConfig = context.configuration().getConfigData(ClprConfig.class);
        if (!clprConfig.endpointManifestEnabled()) {
            log.info("[Clpr] dropped publication from node{} — clpr.endpointManifestEnabled=false", publisherNodeId);
            return;
        }

        final var publication = toStatePublication(op);
        final var constructionStore =
                context.storeFactory().writableStore(WritableEndpointManifestConstructionStore.class);

        // The publication IS the trigger. If a construction is already gathering, route
        // this publication into it (deterministic: publisher id from creatorInfo, target set on the
        // construction). If none is in flight, OPEN one — but only when this is a fresh endpoint
        // that the manifest does not already contain (full-value membership), so unchanged
        // re-publications don't churn a construction. Both decisions are pure functions of
        // consensus-visible state (the publication + manifest + roster), so every node reaches the
        // same result — no divergence.
        if (constructionStore.get() != null) {
            final boolean admitted = constructionStore.admitPublication(publisherNodeId, publication);
            log.info(
                    "[Clpr] {} publication from node{} into active construction",
                    admitted ? "admitted" : "dropped",
                    publisherNodeId);
            return;
        }

        final var manifestStore = context.storeFactory().readableStore(ReadableEndpointManifestStore.class);
        final var manifest = manifestStore.get();
        if (manifest.endpoints().contains(publication.endpointOrThrow())) {
            // The manifest already reflects exactly this endpoint — nothing changed; do not open.
            log.debug("[Clpr] node{} publication already in manifest — no construction opened", publisherNodeId);
            return;
        }
        final var targetNodeIds = activeRosterNodeIds(context);
        if (targetNodeIds == null || !targetNodeIds.contains(publisherNodeId)) {
            log.info(
                    "[Clpr] dropped publication from node{} — not in the active roster (cannot open construction)",
                    publisherNodeId);
            return;
        }
        final long constructionId = manifest.version() + 1;
        final var opened = ClprEndpointManifestConstruction.newBuilder()
                .constructionId(constructionId)
                .targetNodeIds(targetNodeIds)
                .gatheredPublications(List.of(ClprEndpointPublicationEntry.newBuilder()
                        .nodeId(publisherNodeId)
                        .publication(publication)
                        .build()))
                .gracePeriodEndTime(toTimestamp(context.consensusNow().plus(clprConfig.manifestGracePeriod())))
                .graceExtensionsUsed(0)
                .build();
        constructionStore.put(opened);
        log.info(
                "[Clpr] opened endpoint-manifest construction #{} on node{}'s changed endpoint (targetNodes={})",
                constructionId,
                publisherNodeId,
                targetNodeIds.size());
    }

    /** Active-roster node ids, sorted ascending, or {@code null} if the roster is unavailable. */
    @Nullable
    private static List<Long> activeRosterNodeIds(@NonNull final HandleContext context) {
        final var rosterStore = context.storeFactory().readableStore(ReadableRosterStore.class);
        final var activeRoster = rosterStore.getActiveRoster();
        if (activeRoster == null) {
            return null;
        }
        return activeRoster.rosterEntries().stream()
                .map(RosterEntry::nodeId)
                .sorted()
                .toList();
    }

    private static ClprEndpointPublication toStatePublication(
            @NonNull final ClprEndpointPublicationTransactionBody op) {
        // pureChecks guarantees an endpoint is present (no-change acks are rejected).
        return ClprEndpointPublication.newBuilder()
                .endpoint(op.endpointOrThrow())
                .build();
    }
}

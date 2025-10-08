// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import com.hedera.hapi.block.stream.ChainOfTrustProof;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.node.app.history.handlers.HistoryHandlers;
import com.hedera.node.app.history.impl.OnProofFinished;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.service.roster.impl.RosterServiceImpl;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.Service;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Proves inclusion of metadata in a history of rosters.
 */
public interface HistoryService extends Service, OnProofFinished {
    String NAME = "HistoryService";

    /**
     * Since the roster service has to decide to adopt the candidate roster
     * at an upgrade boundary based on availability of TSS signatures on
     * blocks produced by that roster, the history service must be migrated
     * before the roster service in the node's <i>setup</i> phase. (Contrast
     * with the reverse order of dependency in the <i>runtime</i> phase; then
     * the history service depends on the roster service to know how to set up
     * its ongoing construction work for roster transitions.)
     */
    int MIGRATION_ORDER = RosterServiceImpl.MIGRATION_ORDER - 1;

    @Override
    default @NonNull String getServiceName() {
        return NAME;
    }

    @Override
    default int migrationOrder() {
        return MIGRATION_ORDER;
    }

    /**
     * Returns the handlers for the {@link HistoryService}.
     */
    HistoryHandlers handlers();

    /**
     * Sets the callback for when a proof construction is finished. Only one callback is active at a time.
     * @param cb the callback to set
     */
    void onFinishedConstruction(@Nullable OnProofFinished cb);

    /**
     * Sets the latest history proof.
     * @param historyProof the latest history proof
     */
    void setLatestHistoryProof(@NonNull HistoryProof historyProof);

    /**
     * Whether this service is ready to provide metadata-enriched proofs.
     */
    boolean isReady();

    /**
     * Reconciles the history of roster proofs with the given active rosters and metadata, if known.
     *
     * @param activeRosters the active rosters
     * @param currentMetadata the current metadata, if known
     * @param historyStore the history store
     * @param now the current time
     * @param tssConfig the TSS configuration
     * @param isActive if the platform is active
     * @param activeConstruction the active hinTS construction, if any
     */
    void reconcile(
            @NonNull ActiveRosters activeRosters,
            @Nullable Bytes currentMetadata,
            @NonNull WritableHistoryStore historyStore,
            @NonNull Instant now,
            @NonNull TssConfig tssConfig,
            boolean isActive,
            @Nullable HintsConstruction activeConstruction);

    /**
     * Returns a proof of inclusion of the given metadata for the current roster.
     *
     * @param metadata the metadata that must be included in the proof
     * @return the proof
     * @throws IllegalStateException if the service is not ready
     * @throws IllegalArgumentException if the metadata for the current roster does not match the given metadata
     */
    @NonNull
    ChainOfTrustProof getCurrentChainOfTrustProof(@NonNull Bytes metadata);
}

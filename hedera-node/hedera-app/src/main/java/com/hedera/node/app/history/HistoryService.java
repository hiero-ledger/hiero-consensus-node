// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.node.app.hints.KeyProvenanceSigner;
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
     * Returns true if work on the given construction is completed.
     * @param construction the construction
     * @return true if work on the given construction is completed
     */
    static boolean isCompleted(@NonNull final HistoryProofConstruction construction) {
        return construction.hasTargetProof();
    }

    /**
     * Returns the ledger id of a chain of trust grounded in the target history of the given proof.
     * @param proof the proof grounding a chain of trust
     * @return the ledger id
     */
    @NonNull
    Bytes ledgerIdOf(@NonNull HistoryProof proof);

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
     * Offers a directly signed block witness for consensus publication. The verification key and generation
     * must come from the captured signing attempt, not the current mutable signing context.
     */
    void publishKeyWitness(@NonNull Bytes verificationKey, @NonNull HintsKeyWitness witness);

    /**
     * Whether this service is ready to provide metadata-enriched proofs.
     */
    boolean isReady();

    /**
     * Persists a legacy active proof's complete membership only when available generation weights
     * reproduce its committed address-book hash. Run before consumers resolve signing or vote weights.
     *
     * @param activeRosters the available rosters
     * @param historyStore the history store in which to preserve the recovered snapshot
     * @param activeConstruction the persisted active hinTS construction, if any
     */
    void recoverActiveBook(
            @NonNull ActiveRosters activeRosters,
            @NonNull WritableHistoryStore historyStore,
            @Nullable HintsConstruction activeConstruction);

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
     * @param freshGenesisRequested whether a fresh genesis proof is requested for the current roster
     * @param sourceSigner the immutable persisted source signing generation, absent only before hinTS genesis
     * @param targetHintsConstructionId the generation whose verification key is being certified
     */
    void reconcile(
            @NonNull ActiveRosters activeRosters,
            @Nullable Bytes currentMetadata,
            @NonNull WritableHistoryStore historyStore,
            @NonNull Instant now,
            @NonNull TssConfig tssConfig,
            boolean isActive,
            @Nullable HintsConstruction activeConstruction,
            boolean freshGenesisRequested,
            @Nullable KeyProvenanceSigner sourceSigner,
            long targetHintsConstructionId);

    /**
     * Stops the history service, causing it to abandon any in-progress work.
     */
    void stop();

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

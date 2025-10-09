// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.hints.handlers.HintsHandlers;
import com.hedera.node.app.hints.impl.HintsController;
import com.hedera.node.app.hints.impl.OnHintsFinished;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.service.roster.impl.RosterServiceImpl;
import com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Orchestrates the hinTS algorithms for,
 * <ol>
 *     <li>Silent setup of party keys and hints.</li>
 *     <li>Preprocessing and acceptance of aggregation and verification keys.</li>
 *     <li>Aggregating partial signatures under a provably well-formed aggregate public key.</li>
 * </ol>
 * Note that only the first two of these require <i>deterministic</i> orchestration, since any
 * valid aggregation of partial signatures that exceeds the threshold works equally well. But a
 * strong minority of the current roster must reach deterministic consensus on the first two
 * steps before the third can be attempted.
 * <p>
 * All the expensive cryptographic work being orchestrated happens in threads spawned by the
 * service, and <i>not</i> in the {@code preHandle} or {@code handleTransaction} threads as
 * they dispatch to the service's {@link com.hedera.node.app.spi.workflows.TransactionHandler}s.
 * When a background thread finishes some costly cryptographic work for a node, it gossips
 * the result to the rest of the network via a {@link TransactionCategory#NODE} transaction.
 * <p>
 * All honest nodes will incorporate results deterministically, depending on the type of
 * result. If it is an individual result like a hint, then all honest nodes will adhere to
 * a deterministic policy for adopting a particular set of valid results. If it is an
 * aggregate result like a verification key, all honest nodes will wait to adopt it until
 * a strong minority of the current roster has approved that exact result.
 * <p>
 * The service only supports orchestration of one ongoing hinTS construction at a time,
 * and if requested to orchestrate a different construction, will abandon all in-progress
 * work.
 */
public interface HintsService extends Service, BlockHashSigner {
    String NAME = "HintsService";

    /**
     * Since the roster service has to decide to adopt the candidate roster
     * at an upgrade boundary based on availability of hinTS preprocessed
     * keys, the hinTS service must be migrated before the roster service
     * in the node's <i>setup</i> phase. (Contrast with the reverse order of
     * dependency in the <i>runtime</i> phase; then the hinTS service depends
     * on the roster service to know how to set up preprocessing work.)
     */
    int MIGRATION_ORDER = RosterServiceImpl.MIGRATION_ORDER - 1;

    /**
     * Placeholder for the history service to use when hinTS is disabled.
     */
    Bytes DISABLED_HINTS_METADATA = Bytes.wrap(new byte[1288]);

    /**
     * Returns the active verification key, or throws if none is active.
     */
    @NonNull
    Bytes activeVerificationKeyOrThrow();

    /**
     * Sets the current roster for the network.
     * @param roster the roster
     */
    void initCurrentRoster(@NonNull Roster roster);

    /**
     * Sets the callback for when a hinTS construction is finished. Only one callback is active at a time.
     * @param cb the callback to invoke with the current writable hints store
     */
    void onFinishedConstruction(@Nullable OnHintsFinished cb);

    /**
     * Initializes hinTS signing from the next construction in the given {@link WritableHintsStore}.
     *
     * @param hintsStore the hints store
     * @param previousRoster the previous roster
     * @param adoptedRoster the adopted roster
     * @param adoptedRosterHash the adopted roster hash
     * @param forceHandoff whether to force the handoff when the adopted roster hash doesn't match the next construction
     */
    void manageRosterAdoption(
            @NonNull WritableHintsStore hintsStore,
            @NonNull Roster previousRoster,
            @NonNull Roster adoptedRoster,
            @NonNull Bytes adoptedRosterHash,
            boolean forceHandoff);

    /**
     * Takes any actions needed to advance the state of the {@link HintsService} toward
     * having completed the most up-to-date hinTS construction for the given {@link ActiveRosters}.
     * <p>
     * Given active rosters with a source/target transition, this method will,
     * <ol>
     *     <Li>Do nothing if a completed construction for the transition already exists in {@link HintsService}.</Li>
     *     <Li>If there is no active {@link HintsController} for the transition, create one based
     *     on the given consensus time and {@link HintsService} states; and save the created construction in
     *     network state if this is the first time the network ever began reconciling a hinTS construction for
     *     the transition.</Li>
     *     <Li>For the resolved {@link HintsController} for the transition, invoke its
     *     {@link HintsController#advanceConstruction(Instant, WritableHintsStore, boolean)} method.</li>
     * </ol>
     * <p>
     * <b>Important:</b> Note that whether a new {@link HintsController} is created, or an appropriate
     * one already exists, its subsequent behavior will be a deterministic function of the given consensus time and
     * {@link HintsService} states. That is, controllers are persistent objects <i>only</i> due to performance
     * considerations, but are <i>logically</i> functions of just the network state and consensus time.
     *  @param activeRosters the active rosters
     *
     * @param hintsStore            the hints store, for recording progress if needed
     * @param now                   the current consensus time
     * @param tssConfig             the TSS configuration
     * @param isActive              if the platform is active
     */
    void reconcile(
            @NonNull ActiveRosters activeRosters,
            @NonNull WritableHintsStore hintsStore,
            @NonNull Instant now,
            @NonNull TssConfig tssConfig,
            boolean isActive);

    /**
     * Executes the work needed to set the CRS for the network and start the preprocessing vote.
     *
     * @param hintsStore            the hints store
     * @param now                   the current consensus time
     * @param isActive               if the platform is active
     */
    void executeCrsWork(@NonNull WritableHintsStore hintsStore, @NonNull Instant now, boolean isActive);

    /**
     * Stops the hinTS service, causing it to abandon any in-progress work.
     */
    void stop();

    /**
     * Returns the handlers for the {@link HintsService}.
     */
    HintsHandlers handlers();

    @Override
    default int migrationOrder() {
        return MIGRATION_ORDER;
    }

    @Override
    default @NonNull String getServiceName() {
        return NAME;
    }

    @Override
    void registerSchemas(@NonNull SchemaRegistry registry);

    /**
     * Returns the party size for the given roster.
     * @param roster the roster
     */
    static int partySizeForRoster(@NonNull final Roster roster) {
        requireNonNull(roster);
        return partySizeForRosterNodeCount(roster.rosterEntries().size());
    }

    /**
     * Returns the smallest power of 2 {@code M = 2^k} such that {@code numSigners + 1 < M}. Equivalently,
     * if {@code 2^(k-1) <= numSigners + 1 < 2^k}, then the returned party size is {@code 2^k}.
     *
     * @param numSigners the number of signers (roster node count)
     * @return the party size
     */
    static int partySizeForRosterNodeCount(final int numSigners) {
        // We want the smallest power of two > (numSigners + 1)
        final var candidate = numSigners + 2;
        if ((candidate & (candidate - 1)) == 0) {
            return candidate;
        }
        return Integer.highestOneBit(candidate) << 1;
    }
}

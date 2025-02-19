/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.history;

import com.hedera.node.app.history.handlers.HistoryHandlers;
import com.hedera.node.app.roster.ActiveRosters;
import com.hedera.node.app.roster.RosterService;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.Service;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Proves inclusion of metadata in a history of rosters.
 */
public interface HistoryService extends Service {
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
    int MIGRATION_ORDER = RosterService.MIGRATION_ORDER - 1;

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
     * Whether this service is ready to provide metadata-enriched proofs.
     */
    boolean isReady();

    /**
     * Reconciles the history of roster proofs with the given active rosters and metadata, if known.
     * @param activeRosters the active rosters
     * @param currentMetadata the current metadata, if known
     * @param historyStore the history store
     * @param now the current time
     * @param tssConfig the TSS configuration
     */
    void reconcile(
            @NonNull ActiveRosters activeRosters,
            @Nullable Bytes currentMetadata,
            @NonNull WritableHistoryStore historyStore,
            @NonNull Instant now,
            @NonNull TssConfig tssConfig);

    /**
     * Returns a proof of inclusion of the given metadata for the current roster.
     * @param metadata the metadata that must be included in the proof
     * @return the proof
     * @throws IllegalStateException if the service is not ready
     * @throws IllegalArgumentException if the metadata for the current roster does not match the given metadata
     */
    @NonNull
    Bytes getCurrentProof(@NonNull Bytes metadata);
}

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

package com.hedera.services.bdd.junit.hedera.embedded.fakes;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.handlers.HistoryHandlers;
import com.hedera.node.app.roster.ActiveRosters;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

public class FakeHistoryService implements HistoryService {
    private final HistoryService delegate;

    public FakeHistoryService() {
        delegate = null;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void reconcile(
            @NonNull final ActiveRosters activeRosters,
            @Nullable final Bytes currentMetadata,
            @NonNull final WritableHistoryStore historyStore,
            @NonNull final Instant now,
            @NonNull final TssConfig tssConfig) {
        requireNonNull(activeRosters);
        requireNonNull(historyStore);
        requireNonNull(now);
        requireNonNull(tssConfig);
        // No-op
    }

    @NonNull
    @Override
    public Bytes getCurrentProof(@NonNull final Bytes metadata) {
        return Bytes.EMPTY;
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        requireNonNull(registry);
    }

    @Override
    public HistoryHandlers handlers() {
        throw new AssertionError("Not implemented");
    }
}

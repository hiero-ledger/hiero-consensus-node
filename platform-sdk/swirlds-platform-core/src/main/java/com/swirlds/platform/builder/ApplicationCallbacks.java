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

package com.swirlds.platform.builder;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.consensus.ConsensusSnapshot;
import com.swirlds.platform.event.PlatformEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A collection of callbacks that the application can provide to the platform to be notified of certain events.
 *
 * @param preconsensusEventConsumer a consumer that will be called on preconsensus events in topological order
 * @param snapshotOverrideConsumer  a consumer that will be called when the current consensus snapshot is overridden
 *                                  (i.e. at reconnect/restart boundaries)
 * @param staleEventConsumer        a consumer that will be called when a stale self event is detected
 */
public record ApplicationCallbacks(
        @Nullable Consumer<PlatformEvent> preconsensusEventConsumer,
        @Nullable Consumer<ConsensusSnapshot> snapshotOverrideConsumer,
        @Nullable Consumer<PlatformEvent> staleEventConsumer,
        @NonNull Function<StateSignatureTransaction, Bytes> systemTransactionEncoder) {}

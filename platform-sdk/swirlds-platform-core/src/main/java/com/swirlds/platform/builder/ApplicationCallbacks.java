// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.Consumer;
import java.util.function.Function;
import org.hiero.consensus.model.event.PlatformEvent;

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

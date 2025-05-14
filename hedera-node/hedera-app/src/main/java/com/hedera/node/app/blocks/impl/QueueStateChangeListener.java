// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.swirlds.state.StateChangeListener.StateType.QUEUE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.QueuePopChange;
import com.hedera.hapi.block.stream.output.QueuePushChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.recordcache.TransactionReceiptEntries;
import com.hedera.pbj.runtime.OneOf;
import com.swirlds.state.StateChangeListener;
import com.swirlds.state.merkle.StateUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class QueueStateChangeListener implements StateChangeListener {
    private static final Set<StateType> TARGET_DATA_TYPES = EnumSet.of(QUEUE);

    private final List<StateChange> stateChanges = new ArrayList<>();

    /**
     * Resets the state changes.
     */
    public void reset() {
        stateChanges.clear();
    }

    @Override
    public Set<StateType> stateTypes() {
        return TARGET_DATA_TYPES;
    }

    @Override
    public int stateIdFor(@NonNull final String serviceName, @NonNull final String stateKey) {
        Objects.requireNonNull(serviceName, "serviceName must not be null");
        Objects.requireNonNull(stateKey, "stateKey must not be null");

        return StateUtils.stateIdFor(serviceName, stateKey);
    }

    @Override
    public <V> void queuePushChange(final int stateId, @NonNull final V value) {
        requireNonNull(value);
        final var stateChange = StateChange.newBuilder()
                .stateId(stateId)
                .queuePush(new QueuePushChange(queuePushChangeValueFor(value)))
                .build();
        stateChanges.add(stateChange);
    }

    @Override
    public void queuePopChange(final int stateId) {
        final var stateChange = StateChange.newBuilder()
                .stateId(stateId)
                .queuePop(new QueuePopChange())
                .build();
        stateChanges.add(stateChange);
    }

    /**
     * Returns the list of state changes.
     * @return the list of state changes
     */
    public List<StateChange> getStateChanges() {
        return stateChanges;
    }

    private static <V> OneOf<QueuePushChange.ValueOneOfType> queuePushChangeValueFor(@NonNull final V value) {
        switch (value) {
            case ProtoBytes protoBytesElement -> {
                return new OneOf<>(QueuePushChange.ValueOneOfType.PROTO_BYTES_ELEMENT, protoBytesElement.value());
            }
            case TransactionReceiptEntries transactionReceiptEntriesElement -> {
                return new OneOf<>(
                        QueuePushChange.ValueOneOfType.TRANSACTION_RECEIPT_ENTRIES_ELEMENT,
                        transactionReceiptEntriesElement);
            }
            default ->
                throw new IllegalArgumentException(
                        "Unknown value type " + value.getClass().getName());
        }
    }
}

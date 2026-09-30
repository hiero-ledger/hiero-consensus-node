// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.pbj.runtime.OneOf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Retains supported entity values while translating a complete stream from genesis (or seeded
 * full updates). A production replay consumer should instead keep these values in its state store.
 * Instances are confined to one ordered replay and must never be shared between histories.
 */
public final class StateChangeReconstructor {
    private record Entry(int stateId, MapChangeKey key) {}

    private final Map<Entry, Object> maps = new HashMap<>();
    private final Map<Integer, Object> singletons = new HashMap<>();

    /**
     * Expands values for record/trace translation only. The returned block has different bytes;
     * its original proofs MUST NOT be checked against these bytes. Always verify the original block.
     */
    public Block fullValuesForTranslation(final Block block) {
        StateChangeDeltas.validateBlockDeltas(block);
        final var items = new ArrayList<BlockItem>(block.items().size());
        for (final var item : block.items()) {
            items.add(
                    item.hasStateChanges()
                            ? item.copyBuilder()
                                    .stateChanges(reconstruct(item.stateChangesOrThrow()))
                                    .build()
                            : item);
        }
        return block.copyBuilder().items(items).build();
    }

    /** Applies changes in stream order and returns full-value equivalents. */
    public StateChanges reconstruct(final StateChanges changes) {
        final var output = new ArrayList<StateChange>(changes.stateChanges().size());
        for (final var change : changes.stateChanges()) {
            StateChange full = change;
            if (change.hasMapUpdate()) {
                final var update = change.mapUpdateOrThrow();
                final var choice = update.valueOrThrow().valueChoice();
                final var entry = new Entry(change.stateId(), update.keyOrThrow());
                if (update.partial()
                        || StateChangeDeltas.mapType(
                                        change.stateId(), choice.kind().protoOrdinal())
                                != null) {
                    final var value = StateChangeDeltas.applyMap(change.stateId(), maps.get(entry), update);
                    maps.put(entry, value);
                    full = change.copyBuilder()
                            .mapUpdate(update.copyBuilder()
                                    .value(new MapChangeValue(new OneOf<>(
                                            choice.kind(),
                                            StateChangeDeltas.mapType(
                                                            change.stateId(),
                                                            choice.kind().protoOrdinal())
                                                    .wireValue(value))))
                                    .partial(false)
                                    .clearedFields(List.of()))
                            .build();
                } else {
                    maps.remove(entry);
                }
            } else if (change.hasMapDelete()) {
                maps.remove(
                        new Entry(change.stateId(), change.mapDeleteOrThrow().keyOrThrow()));
            } else if (change.hasSingletonUpdate()) {
                final var update = change.singletonUpdateOrThrow();
                if (update.partial()
                        || StateChangeDeltas.singletonType(
                                        change.stateId(),
                                        update.newValue().kind().protoOrdinal())
                                != null) {
                    final var value = StateChangeDeltas.applySingleton(
                            change.stateId(), singletons.get(change.stateId()), update);
                    singletons.put(change.stateId(), value);
                    full = change.copyBuilder()
                            .singletonUpdate(new SingletonUpdateChange(
                                    false,
                                    List.of(),
                                    new OneOf<>(
                                            update.newValue().kind(),
                                            StateChangeDeltas.singletonType(
                                                            change.stateId(),
                                                            update.newValue()
                                                                    .kind()
                                                                    .protoOrdinal())
                                                    .wireValue(value)),
                                    update.getUnknownFields()))
                            .build();
                } else {
                    singletons.remove(change.stateId());
                }
            } else if (change.hasStateRemove()) {
                maps.keySet().removeIf(entry -> entry.stateId() == change.stateId());
                singletons.remove(change.stateId());
            }
            output.add(full);
        }
        return changes.copyBuilder().stateChanges(output).build();
    }
}

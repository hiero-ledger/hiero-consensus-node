// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.util;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.utility.Keyed;
import com.swirlds.common.test.fixtures.dummy.Key;
import com.swirlds.common.test.fixtures.dummy.Value;
import com.swirlds.merkle.test.fixtures.map.dummy.FCQValue;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

public enum KeyValueProvider {
    KEY_VALUE(
            MapMutatorSet.ValueType.SIMPLE,
            () -> new Key(1, 1, 1),
            () -> Value.newBuilder()
                    .setReceiveThresholdValue(12345L)
                    .setSendThresholdvalue(54321L)
                    .setBalance(98766)
                    .setReceiveSignatureRequired(true)
                    .build(),
            (index) -> new Key(index, index, index),
            Value::buildRandomValue),

    KEY_FCQ(
            MapMutatorSet.ValueType.FCQ,
            () -> new Key(1, 1, 1),
            FCQValue::buildDefault,
            (index) -> new Key(index, index, index),
            FCQValue::buildRandom);

    private final MapMutatorSet mapMutatorSet;

    private final Supplier<Key> defaultKey;

    private final Supplier<MerkleNode> defaultValue;

    private final Function<Integer, Key> keyFactory;

    private final Supplier<MerkleNode> randomValueFactory;

    @SuppressWarnings("unchecked")
    <V extends MerkleNode> KeyValueProvider(
            final MapMutatorSet.ValueType valueType,
            final Supplier<Key> defaultKey,
            final Supplier<V> defaultValue,
            final Function<Integer, Key> keyFactory,
            final Supplier<V> randomValueFactory) {
        this.mapMutatorSet = new MapMutatorSet(valueType);
        this.defaultKey = defaultKey;
        this.defaultValue = (Supplier<MerkleNode>) defaultValue;
        this.keyFactory = keyFactory;
        this.randomValueFactory = (Supplier<MerkleNode>) randomValueFactory;
    }

    public <V extends MerkleNode> void insertIntoMap(final int startIndex, final int endIndex, final Map<Key, V> fcm) {
        mapMutatorSet.insertIntoMap(startIndex, endIndex, fcm);
    }

    public <V extends MerkleNode & Keyed<Key>> void updateMap(final Map<Key, V> fcm, final int percentage) {
        mapMutatorSet.updateValueInMap(fcm, percentage);
    }

    public <V extends MerkleNode & Keyed<Key>> void deleteFromMap(final Map<Key, V> fcm, final int percentage) {
        mapMutatorSet.deleteFromMap(fcm, percentage);
    }

    public Key getDefaultKey() {
        return this.defaultKey.get();
    }

    @SuppressWarnings("unchecked")
    public <V extends MerkleNode> V getDefaultValue() {
        return (V) this.defaultValue.get();
    }

    public Key getKeyBasedOnIndex(final int index) {
        return this.keyFactory.apply(index);
    }

    @SuppressWarnings("unchecked")
    public <V extends MerkleNode> V buildRandomValue() {
        return (V) this.randomValueFactory.get();
    }
}

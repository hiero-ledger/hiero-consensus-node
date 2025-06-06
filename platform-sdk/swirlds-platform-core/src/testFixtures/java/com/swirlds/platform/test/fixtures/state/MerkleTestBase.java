// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.pbj.runtime.Codec;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.utility.Labeled;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.MerkleStateRoot;
import com.swirlds.state.merkle.disk.OnDiskKey;
import com.swirlds.state.merkle.disk.OnDiskValue;
import com.swirlds.state.merkle.memory.InMemoryKey;
import com.swirlds.state.merkle.memory.InMemoryValue;
import com.swirlds.state.test.fixtures.StateTestBase;
import com.swirlds.state.test.fixtures.merkle.TestSchema;
import com.swirlds.virtualmap.VirtualMap;

/**
 * This base class provides helpful methods and defaults for simplifying the other merkle related
 * tests in this and sub packages. It is highly recommended to extend from this class.
 *
 * <h1>Services</h1>
 *
 * <p>This class introduces two real services, and one bad service. The real services are called
 * (quite unhelpfully) {@link #FIRST_SERVICE} and {@link #SECOND_SERVICE}. There is also an {@link
 * #UNKNOWN_SERVICE} which is useful for tests where we are trying to look up a service that should
 * not exist.
 *
 * <p>Each service has a number of associated states, based on those defined in {@link
 * StateTestBase}. The {@link #FIRST_SERVICE} has "fruit" and "animal" states, while the {@link
 * #SECOND_SERVICE} has space, steam, and country themed states. Most of these are simple String
 * types for the key and value, but the space themed state uses Long as the key type.
 *
 * <p>This class defines all the {@link Codec}, {@link StateMetadata}, and {@link MerkleMap}s
 * required to represent each of these. It does not create a {@link VirtualMap} automatically, but
 * does provide APIs to make it easy to create them (the {@link VirtualMap} has a lot of setup
 * complexity, and also requires a storage directory, so rather than creating these for every test
 * even if they don't need it, I just use it for virtual map specific tests).
 */
public class MerkleTestBase extends com.swirlds.state.test.fixtures.merkle.MerkleTestBase {

    protected SemanticVersion v1 = SemanticVersion.newBuilder().major(1).build();

    protected StateMetadata<String, String> fruitMetadata;
    protected StateMetadata<String, String> fruitVirtualMetadata;
    protected StateMetadata<String, String> animalMetadata;
    protected StateMetadata<Long, String> spaceMetadata;
    protected StateMetadata<String, String> steamMetadata;
    protected StateMetadata<String, String> countryMetadata;

    /** Sets up the "Fruit" merkle map, label, and metadata. */
    @Override
    protected void setupFruitMerkleMap() {
        super.setupFruitMerkleMap();
        fruitMetadata = new StateMetadata<>(
                FIRST_SERVICE,
                new TestSchema(1),
                StateDefinition.inMemory(FRUIT_STATE_KEY, STRING_CODEC, STRING_CODEC));
    }

    /** Sets up the "Fruit" virtual map, label, and metadata. */
    @Override
    protected void setupFruitVirtualMap() {
        super.setupFruitVirtualMap();
        fruitVirtualMetadata = new StateMetadata<>(
                FIRST_SERVICE,
                new TestSchema(1),
                StateDefinition.onDisk(FRUIT_STATE_KEY, STRING_CODEC, STRING_CODEC, 100));
    }

    /** Sets up the "Animal" merkle map, label, and metadata. */
    @Override
    protected void setupAnimalMerkleMap() {
        super.setupAnimalMerkleMap();
        animalMetadata = new StateMetadata<>(
                FIRST_SERVICE,
                new TestSchema(1),
                StateDefinition.inMemory(ANIMAL_STATE_KEY, STRING_CODEC, STRING_CODEC));
    }

    /** Sets up the "Space" merkle map, label, and metadata. */
    @Override
    protected void setupSpaceMerkleMap() {
        super.setupSpaceMerkleMap();
        spaceMetadata = new StateMetadata<>(
                SECOND_SERVICE, new TestSchema(1), StateDefinition.inMemory(SPACE_STATE_KEY, LONG_CODEC, STRING_CODEC));
    }

    @Override
    protected void setupSingletonCountry() {
        super.setupSingletonCountry();
        countryMetadata = new StateMetadata<>(
                FIRST_SERVICE, new TestSchema(1), StateDefinition.singleton(COUNTRY_STATE_KEY, STRING_CODEC));
    }

    @Override
    protected void setupSteamQueue() {
        super.setupSteamQueue();
        steamMetadata = new StateMetadata<>(
                FIRST_SERVICE, new TestSchema(1), StateDefinition.queue(STEAM_STATE_KEY, STRING_CODEC));
    }

    /** Creates a new arbitrary virtual map with the given label, storageDir, and metadata */
    @SuppressWarnings("unchecked")
    protected VirtualMap<OnDiskKey<String>, OnDiskValue<String>> createVirtualMap(
            String label, StateMetadata<String, String> md) {
        return createVirtualMap(
                label,
                md.onDiskKeySerializerClassId(),
                md.onDiskKeyClassId(),
                md.stateDefinition().keyCodec(),
                md.onDiskValueSerializerClassId(),
                md.onDiskValueClassId(),
                md.stateDefinition().valueCodec());
    }

    /**
     * Looks within the merkle tree for a node with the given label. This is useful for tests that
     * need to verify some change actually happened in the merkle tree.
     */
    protected MerkleNode getNodeForLabel(MerkleStateRoot stateRoot, String label) {
        // This is not idea, as it requires white-box testing -- knowing the
        // internal details of the MerkleStateRoot. But lacking a getter
        // (which I don't want to add), this is what I'm left with!
        for (int i = 0, n = stateRoot.getNumberOfChildren(); i < n; i++) {
            final MerkleNode child = stateRoot.getChild(i);
            if (child instanceof Labeled labeled && label.equals(labeled.getLabel())) {
                return child;
            }
        }

        return null;
    }

    /** A convenience method for adding a k/v pair to a merkle map */
    protected void add(
            MerkleMap<InMemoryKey<String>, InMemoryValue<String, String>> map,
            StateMetadata<String, String> md,
            String key,
            String value) {
        final var def = md.stateDefinition();
        super.add(map, md.inMemoryValueClassId(), def.keyCodec(), def.valueCodec(), key, value);
    }

    /** A convenience method for adding a k/v pair to a virtual map */
    protected void add(
            VirtualMap<OnDiskKey<String>, OnDiskValue<String>> map,
            StateMetadata<String, String> md,
            String key,
            String value) {
        super.add(
                map,
                md.onDiskKeyClassId(),
                md.stateDefinition().keyCodec(),
                md.onDiskValueClassId(),
                md.stateDefinition().valueCodec(),
                key,
                value);
    }
}

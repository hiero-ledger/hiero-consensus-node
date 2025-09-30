// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
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
 * <p>This class defines all the {@link Codec}s and {@link StateMetadata}s required to
 * represent each of these. It does not create a {@link VirtualMap} automatically, but
 * does provide APIs to make it easy to create them (the {@link VirtualMap} has a lot of setup
 * complexity, and also requires a storage directory, so rather than creating these for every test
 * even if they don't need it, I just use it for virtual map specific tests).
 */
public class MerkleTestBase extends com.swirlds.state.test.fixtures.merkle.MerkleTestBase {

    protected SemanticVersion v1 = SemanticVersion.newBuilder().major(1).build();

    protected StateMetadata<ProtoBytes, ProtoBytes> fruitMetadata;
    protected StateMetadata<ProtoBytes, ProtoBytes> steamMetadata;
    protected StateMetadata<ProtoBytes, ProtoBytes> countryMetadata;

    /** Sets up the "Fruit" virtual map, label, and metadata. */
    @Override
    protected void setupFruitVirtualMap() {
        super.setupFruitVirtualMap();
        fruitMetadata = new StateMetadata<>(
                FIRST_SERVICE,
                new TestSchema(1),
                StateDefinition.onDisk(FRUIT_STATE_ID, FRUIT_STATE_KEY, ProtoBytes.PROTOBUF, ProtoBytes.PROTOBUF, 100));
    }

    @Override
    protected void setupSingletonCountry() {
        super.setupSingletonCountry();
        countryMetadata = new StateMetadata<>(
                FIRST_SERVICE,
                new TestSchema(1),
                StateDefinition.singleton(COUNTRY_STATE_ID, COUNTRY_STATE_KEY, ProtoBytes.PROTOBUF));
    }

    @Override
    protected void setupSteamQueue() {
        super.setupSteamQueue();
        steamMetadata = new StateMetadata<>(
                FIRST_SERVICE,
                new TestSchema(1),
                StateDefinition.queue(STEAM_STATE_ID, STEAM_STATE_KEY, ProtoBytes.PROTOBUF));
    }

    /** A convenience method for adding a singleton state to a virtual map */
    protected void addSingletonState(VirtualMap map, StateMetadata<ProtoBytes, ProtoBytes> md, ProtoBytes value) {
        addSingletonState(map, md.serviceName(), md.stateDefinition().stateId(), value);
    }

    /** A convenience method for adding a k/v state to a virtual map */
    protected void addKvState(
            VirtualMap map, StateMetadata<ProtoBytes, ProtoBytes> md, ProtoBytes key, ProtoBytes value) {
        addKvState(map, md.serviceName(), md.stateDefinition().stateId(), key, value);
    }
}

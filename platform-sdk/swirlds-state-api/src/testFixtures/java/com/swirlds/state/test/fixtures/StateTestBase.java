// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures;

import static org.mockito.ArgumentMatchers.any;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.WritableSingletonState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicReference;

public class StateTestBase extends TestBase {
    public static final SemanticVersion TEST_VERSION =
            SemanticVersion.newBuilder().major(1).build();

    public static final String FIRST_SERVICE = "First-Service";

    public static final String SECOND_SERVICE = "Second-Service";

    public static final String UNKNOWN_SERVICE = "Bogus-Service";
    protected static final String UNKNOWN_STATE_KEY = "BOGUS_STATE_KEY";
    protected static final ProtoBytes UNKNOWN_KEY = new ProtoBytes(Bytes.wrap("BOGUS_KEY"));

    protected static final String FRUIT_SERVICE_NAME = "Plants";
    protected static final String FRUIT_STATE_KEY = "FRUIT";
    protected static final int FRUIT_STATE_ID = 3;

    protected static final String ANIMAL_SERVICE_NAME = "Organisms";
    protected static final String ANIMAL_STATE_KEY = "ANIMAL";
    protected static final int ANIMAL_STATE_ID = 16;

    protected static final String SPACE_SERVICE_NAME = "Universe";
    protected static final String SPACE_STATE_KEY = "SPACE";
    protected static final int SPACE_STATE_ID = 1;

    protected static final String STEAM_SERVICE_NAME = "Learning";
    protected static final String STEAM_STATE_KEY = "STEAM";
    protected static final int STEAM_STATE_ID = 10001;

    public static final String COUNTRY_SERVICE_NAME = "Planets";
    public static final String COUNTRY_STATE_KEY = "COUNTRY";
    protected static final int COUNTRY_STATE_ID = 11;

    protected static final ProtoBytes A_KEY = toProtoBytes("A");
    protected static final ProtoBytes B_KEY = toProtoBytes("B");
    protected static final ProtoBytes C_KEY = toProtoBytes("C");
    protected static final ProtoBytes D_KEY = toProtoBytes("D");
    protected static final ProtoBytes E_KEY = toProtoBytes("E");
    protected static final ProtoBytes F_KEY = toProtoBytes("F");
    protected static final ProtoBytes G_KEY = toProtoBytes("G");

    protected static final ProtoBytes APPLE = toProtoBytes("Apple");
    protected static final ProtoBytes ACAI = toProtoBytes("Acai");
    protected static final ProtoBytes BANANA = toProtoBytes("Banana");
    protected static final ProtoBytes BLACKBERRY = toProtoBytes("BlackBerry");
    protected static final ProtoBytes BLUEBERRY = toProtoBytes("BlueBerry");
    protected static final ProtoBytes CHERRY = toProtoBytes("Cherry");
    protected static final ProtoBytes CRANBERRY = toProtoBytes("Cranberry");
    protected static final ProtoBytes DATE = toProtoBytes("Date");
    protected static final ProtoBytes DRAGONFRUIT = toProtoBytes("DragonFruit");
    protected static final ProtoBytes EGGPLANT = toProtoBytes("Eggplant");
    protected static final ProtoBytes ELDERBERRY = toProtoBytes("ElderBerry");
    protected static final ProtoBytes FIG = toProtoBytes("Fig");
    protected static final ProtoBytes FEIJOA = toProtoBytes("Feijoa");
    protected static final ProtoBytes GRAPE = toProtoBytes("Grape");

    protected static final ProtoBytes AARDVARK = toProtoBytes("Aardvark");
    protected static final ProtoBytes BEAR = toProtoBytes("Bear");
    protected static final ProtoBytes CUTTLEFISH = toProtoBytes("Cuttlefish");
    protected static final ProtoBytes DOG = toProtoBytes("Dog");
    protected static final ProtoBytes EMU = toProtoBytes("Emu");
    protected static final ProtoBytes FOX = toProtoBytes("Fox");
    protected static final ProtoBytes GOOSE = toProtoBytes("Goose");

    protected static final ProtoBytes ASTRONAUT = toProtoBytes("Astronaut");
    protected static final ProtoBytes BLASTOFF = toProtoBytes("Blastoff");
    protected static final ProtoBytes COMET = toProtoBytes("Comet");
    protected static final ProtoBytes DRACO = toProtoBytes("Draco");
    protected static final ProtoBytes EXOPLANET = toProtoBytes("Exoplanet");
    protected static final ProtoBytes FORCE = toProtoBytes("Force");
    protected static final ProtoBytes GRAVITY = toProtoBytes("Gravity");

    protected static final ProtoBytes ART = toProtoBytes("Art");
    protected static final ProtoBytes BIOLOGY = toProtoBytes("Biology");
    protected static final ProtoBytes CHEMISTRY = toProtoBytes("Chemistry");
    protected static final ProtoBytes DISCIPLINE = toProtoBytes("Discipline");
    protected static final ProtoBytes ECOLOGY = toProtoBytes("Ecology");
    protected static final ProtoBytes FIELDS = toProtoBytes("Fields");
    protected static final ProtoBytes GEOMETRY = toProtoBytes("Geometry");

    protected static final ProtoBytes AUSTRALIA = toProtoBytes("Australia");
    protected static final ProtoBytes BRAZIL = toProtoBytes("Brazil");
    protected static final ProtoBytes CHAD = toProtoBytes("Chad");
    protected static final ProtoBytes DENMARK = toProtoBytes("Denmark");
    protected static final ProtoBytes ESTONIA = toProtoBytes("Estonia");
    protected static final ProtoBytes FRANCE = toProtoBytes("France");
    protected static final ProtoBytes GHANA = toProtoBytes("Ghana");

    @NonNull
    protected MapReadableKVState<ProtoBytes, ProtoBytes> readableFruitState() {
        return MapReadableKVState.<ProtoBytes, ProtoBytes>builder(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY)
                .value(A_KEY, APPLE)
                .value(B_KEY, BANANA)
                .value(C_KEY, CHERRY)
                .value(D_KEY, DATE)
                .value(E_KEY, EGGPLANT)
                .value(F_KEY, FIG)
                .value(G_KEY, GRAPE)
                .build();
    }

    @NonNull
    protected MapWritableKVState<ProtoBytes, ProtoBytes> writableFruitState() {
        return MapWritableKVState.<ProtoBytes, ProtoBytes>builder(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY)
                .value(A_KEY, APPLE)
                .value(B_KEY, BANANA)
                .value(C_KEY, CHERRY)
                .value(D_KEY, DATE)
                .value(E_KEY, EGGPLANT)
                .value(F_KEY, FIG)
                .value(G_KEY, GRAPE)
                .build();
    }

    @NonNull
    protected MapReadableKVState<ProtoBytes, ProtoBytes> readableAnimalState() {
        return MapReadableKVState.<ProtoBytes, ProtoBytes>builder(ANIMAL_SERVICE_NAME, ANIMAL_STATE_KEY)
                .value(A_KEY, AARDVARK)
                .value(B_KEY, BEAR)
                .value(C_KEY, CUTTLEFISH)
                .value(D_KEY, DOG)
                .value(E_KEY, EMU)
                .value(F_KEY, FOX)
                .value(G_KEY, GOOSE)
                .build();
    }

    @NonNull
    protected MapWritableKVState<ProtoBytes, ProtoBytes> writableAnimalState() {
        return MapWritableKVState.<ProtoBytes, ProtoBytes>builder(ANIMAL_SERVICE_NAME, ANIMAL_STATE_KEY)
                .value(A_KEY, AARDVARK)
                .value(B_KEY, BEAR)
                .value(C_KEY, CUTTLEFISH)
                .value(D_KEY, DOG)
                .value(E_KEY, EMU)
                .value(F_KEY, FOX)
                .value(G_KEY, GOOSE)
                .build();
    }

    @NonNull
    protected ReadableSingletonState<ProtoBytes> readableSpaceState() {
        return new FunctionReadableSingletonState<>(SPACE_SERVICE_NAME, SPACE_STATE_KEY, () -> ASTRONAUT);
    }

    @NonNull
    protected WritableSingletonState<ProtoBytes> writableSpaceState() {
        final AtomicReference<ProtoBytes> backingValue = new AtomicReference<>(ASTRONAUT);
        return new FunctionWritableSingletonState<>(
                SPACE_SERVICE_NAME, SPACE_STATE_KEY, backingValue::get, backingValue::set);
    }

    @NonNull
    protected ListReadableQueueState<ProtoBytes> readableSTEAMState() {
        return ListReadableQueueState.<ProtoBytes>builder(STEAM_STATE_KEY, STEAM_SERVICE_NAME)
                .value(ART)
                .value(BIOLOGY)
                .value(CHEMISTRY)
                .value(DISCIPLINE)
                .value(ECOLOGY)
                .value(FIELDS)
                .value(GEOMETRY)
                .build();
    }

    @NonNull
    protected ListWritableQueueState<ProtoBytes> writableSTEAMState() {
        return ListWritableQueueState.<ProtoBytes>builder(STEAM_SERVICE_NAME, STEAM_STATE_KEY)
                .value(ART)
                .value(BIOLOGY)
                .value(CHEMISTRY)
                .value(DISCIPLINE)
                .value(ECOLOGY)
                .value(FIELDS)
                .value(GEOMETRY)
                .build();
    }

    @NonNull
    protected ReadableSingletonState<ProtoBytes> readableCountryState() {
        return new FunctionReadableSingletonState<>(COUNTRY_SERVICE_NAME, COUNTRY_STATE_KEY, () -> AUSTRALIA);
    }

    @NonNull
    protected WritableSingletonState<ProtoBytes> writableCountryState() {
        final AtomicReference<ProtoBytes> backingValue = new AtomicReference<>(AUSTRALIA);
        return new FunctionWritableSingletonState<>(
                COUNTRY_SERVICE_NAME, COUNTRY_STATE_KEY, backingValue::get, backingValue::set);
    }

    /** A convenience method for creating {@link SemanticVersion}. */
    protected SemanticVersion version(int major, int minor, int patch) {
        return new SemanticVersion(major, minor, patch, null, null);
    }

    protected static ProtoBytes anyProtoBytes() {
        return any(ProtoBytes.class);
    }

    protected static ProtoBytes toProtoBytes(final String value) {
        return new ProtoBytes(Bytes.wrap(value));
    }
}

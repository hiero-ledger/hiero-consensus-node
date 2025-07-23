// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures;

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

    protected static ProtoBytes toProtoBytes(final String value) {
        return new ProtoBytes(Bytes.wrap(value));
    }

    protected static final String APPLE = "Apple";
    protected static final String ACAI = "Acai";
    protected static final String BANANA = "Banana";
    protected static final String BLACKBERRY = "BlackBerry";
    protected static final String BLUEBERRY = "BlueBerry";
    protected static final String CHERRY = "Cherry";
    protected static final String CRANBERRY = "Cranberry";
    protected static final String DATE = "Date";
    protected static final String DRAGONFRUIT = "DragonFruit";
    protected static final String EGGPLANT = "Eggplant";
    protected static final String ELDERBERRY = "ElderBerry";
    protected static final String FIG = "Fig";
    protected static final String FEIJOA = "Feijoa";
    protected static final String GRAPE = "Grape";

    protected static final String AARDVARK = "Aardvark";
    protected static final String BEAR = "Bear";
    protected static final String CUTTLEFISH = "Cuttlefish";
    protected static final String DOG = "Dog";
    protected static final String EMU = "Emu";
    protected static final String FOX = "Fox";
    protected static final String GOOSE = "Goose";

    protected static final String ASTRONAUT = "Astronaut";
    protected static final String BLASTOFF = "Blastoff";
    protected static final String COMET = "Comet";
    protected static final String DRACO = "Draco";
    protected static final String EXOPLANET = "Exoplanet";
    protected static final String FORCE = "Force";
    protected static final String GRAVITY = "Gravity";

    protected static final String ART = "Art";
    protected static final String BIOLOGY = "Biology";
    protected static final String CHEMISTRY = "Chemistry";
    protected static final String DISCIPLINE = "Discipline";
    protected static final String ECOLOGY = "Ecology";
    protected static final String FIELDS = "Fields";
    protected static final String GEOMETRY = "Geometry";

    protected static final String AUSTRALIA = "Australia";
    protected static final String BRAZIL = "Brazil";
    protected static final String CHAD = "Chad";
    protected static final String DENMARK = "Denmark";
    protected static final String ESTONIA = "Estonia";
    protected static final String FRANCE = "France";
    protected static final String GHANA = "Ghana";

    @NonNull
    protected MapReadableKVState<ProtoBytes, String> readableFruitState() {
        return MapReadableKVState.<ProtoBytes, String>builder(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY)
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
    protected MapWritableKVState<ProtoBytes, String> writableFruitState() {
        return MapWritableKVState.<ProtoBytes, String>builder(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY)
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
    protected MapReadableKVState<ProtoBytes, String> readableAnimalState() {
        return MapReadableKVState.<ProtoBytes, String>builder(ANIMAL_SERVICE_NAME, ANIMAL_STATE_KEY)
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
    protected MapWritableKVState<ProtoBytes, String> writableAnimalState() {
        return MapWritableKVState.<ProtoBytes, String>builder(ANIMAL_SERVICE_NAME, ANIMAL_STATE_KEY)
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
    protected ReadableSingletonState<String> readableSpaceState() {
        return new FunctionReadableSingletonState<>(SPACE_SERVICE_NAME, SPACE_STATE_KEY, () -> ASTRONAUT);
    }

    @NonNull
    protected WritableSingletonState<String> writableSpaceState() {
        final AtomicReference<String> backingValue = new AtomicReference<>(ASTRONAUT);
        return new FunctionWritableSingletonState<>(
                SPACE_SERVICE_NAME, SPACE_STATE_KEY, backingValue::get, backingValue::set);
    }

    @NonNull
    protected ListReadableQueueState<String> readableSTEAMState() {
        return ListReadableQueueState.<String>builder(STEAM_STATE_KEY, STEAM_SERVICE_NAME)
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
    protected ListWritableQueueState<String> writableSTEAMState() {
        return ListWritableQueueState.<String>builder(STEAM_SERVICE_NAME, STEAM_STATE_KEY)
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
    protected ReadableSingletonState<String> readableCountryState() {
        return new FunctionReadableSingletonState<>(COUNTRY_SERVICE_NAME, COUNTRY_STATE_KEY, () -> AUSTRALIA);
    }

    @NonNull
    protected WritableSingletonState<String> writableCountryState() {
        final AtomicReference<String> backingValue = new AtomicReference<>(AUSTRALIA);
        return new FunctionWritableSingletonState<>(
                COUNTRY_SERVICE_NAME, COUNTRY_STATE_KEY, backingValue::get, backingValue::set);
    }

    /** A convenience method for creating {@link SemanticVersion}. */
    protected SemanticVersion version(int major, int minor, int patch) {
        return new SemanticVersion(major, minor, patch, null, null);
    }
}

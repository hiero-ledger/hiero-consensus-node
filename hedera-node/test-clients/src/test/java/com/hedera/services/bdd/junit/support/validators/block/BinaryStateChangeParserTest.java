// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.hedera.hapi.block.stream.output.*;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.hapi.utils.blocks.StateChangeDeltas;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.BinaryState;
import java.util.List;
import org.junit.jupiter.api.Test;

class BinaryStateChangeParserTest {
    private static final int ID = 9999;
    private static final MapChangeKey KEY =
            MapChangeKey.newBuilder().accountIdKey(AccountID.DEFAULT).build();

    @Test
    void everyMapAndSingletonPayloadReplaysThroughTheBinaryStateApi() {
        for (var kind : MapChangeValue.ValueChoiceOneOfType.values()) {
            if (kind.protoOrdinal() <= 0) continue;
            final var type = StateChangeDeltas.mapType(ID, kind.protoOrdinal());
            final var value = type.wireValue(type.defaultValue());
            final var state = mock(BinaryState.class);
            when(state.getKv(ID, Bytes.EMPTY)).thenReturn(Bytes.EMPTY);
            final var update = MapUpdateChange.newBuilder()
                    .key(KEY)
                    .value(new MapChangeValue(new OneOf<>(kind, value)))
                    .partial(true)
                    .build();
            apply(state, StateChange.newBuilder().stateId(ID).mapUpdate(update).build());
            verify(state).updateKv(ID, Bytes.EMPTY, Bytes.EMPTY);
        }
        for (var kind : SingletonUpdateChange.NewValueOneOfType.values()) {
            if (kind.protoOrdinal() <= 0) continue;
            final var type = StateChangeDeltas.singletonType(ID, kind.protoOrdinal());
            final var state = mock(BinaryState.class);
            when(state.getSingleton(ID)).thenReturn(Bytes.EMPTY);
            final var update =
                    new SingletonUpdateChange(true, List.of(), new OneOf<>(kind, type.wireValue(type.defaultValue())));
            apply(
                    state,
                    StateChange.newBuilder().stateId(ID).singletonUpdate(update).build());
            verify(state).updateSingleton(ID, Bytes.EMPTY);
        }
    }

    @Test
    void replacesClearsAndSwitchesOneofUsingTheStoredBaseline() {
        final var old = Account.newBuilder()
                .memo("m".repeat(100))
                .tinybarBalance(5)
                .stakedNodeId(7)
                .build();
        final var next = old.copyBuilder()
                .tinybarBalance(0)
                .stakedAccountId(AccountID.DEFAULT)
                .build();
        final var full = MapUpdateChange.newBuilder()
                .key(KEY)
                .value(MapChangeValue.newBuilder().accountValue(next))
                .build();
        final var partial = StateChangeDeltas.encodeMap(ID, old, full);
        assertThat(partial.partial()).isTrue();
        assertThat(partial.clearedFields()).containsExactly(5, 11);
        final var state = mock(BinaryState.class);
        when(state.getKv(ID, Bytes.EMPTY)).thenReturn(Account.PROTOBUF.toBytes(old));
        apply(state, StateChange.newBuilder().stateId(ID).mapUpdate(partial).build());
        verify(state).updateKv(ID, Bytes.EMPTY, Account.PROTOBUF.toBytes(next));
    }

    @Test
    void fullUpdatesAvoidPriorReadsAndMissingPartialBaselineFails() {
        final var full = MapUpdateChange.newBuilder()
                .key(KEY)
                .value(MapChangeValue.newBuilder().accountValue(Account.DEFAULT))
                .build();
        final var state = mock(BinaryState.class);
        apply(state, StateChange.newBuilder().stateId(ID).mapUpdate(full).build());
        verify(state, never()).getKv(anyInt(), any());
        assertThatThrownBy(() -> apply(
                        state,
                        StateChange.newBuilder()
                                .stateId(ID)
                                .mapUpdate(full.copyBuilder().partial(true))
                                .build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateAndInvalidClearsCannotModifyState() {
        final var state = mock(BinaryState.class);
        final var prior = Account.newBuilder().tinybarBalance(5).build();
        when(state.getKv(ID, Bytes.EMPTY)).thenReturn(Account.PROTOBUF.toBytes(prior));
        for (var clears : List.of(List.of(5, 5), List.of(0), List.of(999))) {
            var update = MapUpdateChange.newBuilder()
                    .key(KEY)
                    .value(MapChangeValue.newBuilder().accountValue(Account.DEFAULT))
                    .partial(true)
                    .clearedFields(clears)
                    .build();
            assertThatThrownBy(() -> apply(
                            state,
                            StateChange.newBuilder()
                                    .stateId(ID)
                                    .mapUpdate(update)
                                    .build()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verify(state, never()).updateKv(anyInt(), any(), any());
    }

    @Test
    void missingOldOneofClearCannotModifyState() {
        final var state = mock(BinaryState.class);
        final var prior = Account.newBuilder().stakedNodeId(7).build();
        when(state.getKv(ID, Bytes.EMPTY)).thenReturn(Account.PROTOBUF.toBytes(prior));
        final var update = MapUpdateChange.newBuilder()
                .key(KEY)
                .partial(true)
                .value(MapChangeValue.newBuilder()
                        .accountValue(Account.newBuilder().stakedAccountId(AccountID.DEFAULT)))
                .build();
        assertThatThrownBy(() -> apply(
                        state,
                        StateChange.newBuilder().stateId(ID).mapUpdate(update).build()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(state, never()).updateKv(anyInt(), any(), any());
    }

    private static void apply(BinaryState state, StateChange change) {
        BinaryStateChangeParser.applyStateChanges(
                state,
                StateChanges.PROTOBUF.toBytes(
                        StateChanges.newBuilder().stateChanges(change).build()));
    }
}

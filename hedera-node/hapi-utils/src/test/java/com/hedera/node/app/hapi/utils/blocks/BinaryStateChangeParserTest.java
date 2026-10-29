// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.hedera.hapi.block.stream.output.*;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.AccountTokenAssociation;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.BinaryState;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BinaryStateChangeParserTest {
    private static final int ID = 9999;
    private static final MapChangeKey KEY =
            MapChangeKey.newBuilder().accountIdKey(AccountID.DEFAULT).build();

    @Test
    void historicalFullBlockInfoUsesFieldOneWithoutReadingABaseline() {
        final var state = mock(BinaryState.class);
        // StateChanges -> state_id=9999 -> singleton_update -> block_info_value (field 1), empty.
        BinaryStateChangeParser.applyStateChanges(state, Bytes.fromHex("1207088f4e22020a00"));
        verify(state).updateSingleton(ID, Bytes.EMPTY);
        verify(state, never()).getSingleton(anyInt());
    }

    @ParameterizedTest
    @CsvSource({
        // partial=1, throttle_usage_snapshots_value (field 9)
        "120f088f4e220a88f104014a0412020802, false",
        // partial=1, packed cleared_fields=[1], throttle_usage_snapshots_value
        "1214088f4e220f88f1040192f10401014a0412020802, true",
        // partial=1, unpacked cleared_fields=1, throttle_usage_snapshots_value
        "1213088f4e220e88f1040190f104014a0412020802, true"
    })
    void singletonGoldenWireFixturesUseMetadataFields10001And10002(
            final String wire, final boolean clearsTpsThrottles) {
        final var prior = ThrottleUsageSnapshots.newBuilder()
                .tpsThrottles(List.of(new ThrottleUsageSnapshot(7, null)))
                .gasThrottle(new ThrottleUsageSnapshot(1, null))
                .build();
        final var state = mock(BinaryState.class);
        when(state.getSingleton(ID)).thenReturn(ThrottleUsageSnapshots.PROTOBUF.toBytes(prior));
        final var expected = prior.copyBuilder().gasThrottle(new ThrottleUsageSnapshot(2, null));
        if (clearsTpsThrottles) expected.tpsThrottles(List.of());
        BinaryStateChangeParser.applyStateChanges(state, Bytes.fromHex(wire));
        verify(state).getSingleton(ID);
        verify(state).updateSingleton(ID, ThrottleUsageSnapshots.PROTOBUF.toBytes(expected.build()));
    }

    @Test
    void everyMapAndSingletonPayloadReplaysThroughTheBinaryStateApi() {
        for (var kind : MapChangeValue.ValueChoiceOneOfType.values()) {
            if (kind.protoOrdinal() <= 0) continue;
            final var type = StateChangeDeltas.mapType(kind.protoOrdinal());
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
            final var type = StateChangeDeltas.singletonType(kind.protoOrdinal());
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
        for (var clears : List.of(List.of(5, 5), List.of(0), List.of(536870912))) {
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
    void encoderGeneratedBalanceDeltaPreservesUnchangedAccountFields() {
        final var before = Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(7).build())
                .key(Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build())
                .memo("m".repeat(200))
                .tinybarBalance(10)
                .build();
        final var after = before.copyBuilder().tinybarBalance(11).build();
        final var delta = StateChangeDeltas.encodeMap(
                ID,
                before,
                MapUpdateChange.newBuilder()
                        .key(KEY)
                        .value(MapChangeValue.newBuilder().accountValue(after))
                        .build());
        assertThat(delta.partial()).isTrue();
        final var state = mock(BinaryState.class);
        when(state.getKv(ID, Bytes.EMPTY)).thenReturn(Account.PROTOBUF.toBytes(before));
        apply(state, StateChange.newBuilder().stateId(ID).mapUpdate(delta).build());
        verify(state).updateKv(ID, Bytes.EMPTY, Account.PROTOBUF.toBytes(after));
    }

    @Test
    void encoderGeneratedSingletonClearUsesPayloadAndStoredBaseline() {
        final var before = BlockInfo.newBuilder()
                .blockHashes(Bytes.wrap(new byte[200]))
                .firstConsTimeOfCurrentBlock(new Timestamp(5, 0))
                .build();
        final var after = before.copyBuilder()
                .firstConsTimeOfCurrentBlock((Timestamp) null)
                .build();
        final var delta = StateChangeDeltas.encodeSingleton(
                ID,
                before,
                SingletonUpdateChange.newBuilder().blockInfoValue(after).build());
        assertThat(delta.partial()).isTrue();
        assertThat(delta.clearedFields()).containsExactly(6);
        final var state = mock(BinaryState.class);
        when(state.getSingleton(ID)).thenReturn(BlockInfo.PROTOBUF.toBytes(before));
        apply(state, StateChange.newBuilder().stateId(ID).singletonUpdate(delta).build());
        verify(state).updateSingleton(ID, BlockInfo.PROTOBUF.toBytes(after));
    }

    @Test
    void tokenRelationshipKeysAreNormalizedForReadsWritesAndDeletes() {
        final var account = AccountID.newBuilder().accountNum(7).build();
        final var token = TokenID.newBuilder().tokenNum(8).build();
        final var key = MapChangeKey.newBuilder()
                .tokenRelationshipKey(new AccountTokenAssociation(account, token))
                .build();
        final var normalized = EntityIDPair.PROTOBUF.toBytes(new EntityIDPair(account, token));
        final int stateId = StateIdentifier.STATE_ID_TOKEN_RELS.protoOrdinal();
        final var state = mock(BinaryState.class);
        when(state.getKv(stateId, normalized)).thenReturn(Bytes.EMPTY);
        final var delta = MapUpdateChange.newBuilder()
                .key(key)
                .value(MapChangeValue.newBuilder().accountValue(Account.DEFAULT))
                .partial(true)
                .build();
        apply(state, StateChange.newBuilder().stateId(stateId).mapUpdate(delta).build());
        verify(state).getKv(stateId, normalized);
        verify(state).updateKv(stateId, normalized, Bytes.EMPTY);
        apply(
                state,
                StateChange.newBuilder()
                        .stateId(stateId)
                        .mapDelete(MapDeleteChange.newBuilder().key(key))
                        .build());
        verify(state).removeKv(stateId, normalized);
    }

    @Test
    void legacyTokenRelationshipKeysStillNormalizeBeforeDeltaReads() {
        final int stateId = StateIdentifier.STATE_ID_TOKEN_RELS.protoOrdinal();
        final var account = AccountID.newBuilder().accountNum(7).build();
        final var token = TokenID.newBuilder().tokenNum(8).build();
        final var normalized = EntityIDPair.PROTOBUF.toBytes(new EntityIDPair(account, token));
        final var state = mock(BinaryState.class);
        when(state.getKv(stateId, normalized)).thenReturn(Bytes.EMPTY);
        // Legacy MapChangeKey field 2 used TokenAssociation (token before account).
        BinaryStateChangeParser.applyStateChanges(
                state, Bytes.fromHex("121608092a120a0a12080a0218081202180712020a002001"));
        verify(state).getKv(stateId, normalized);
        verify(state).updateKv(stateId, normalized, Bytes.EMPTY);
        BinaryStateChangeParser.applyStateChanges(state, Bytes.fromHex("12100809320c0a0a12080a02180812021807"));
        verify(state).removeKv(stateId, normalized);
    }

    private static void apply(BinaryState state, StateChange change) {
        BinaryStateChangeParser.applyStateChanges(
                state,
                StateChanges.PROTOBUF.toBytes(
                        StateChanges.newBuilder().stateChanges(change).build()));
    }
}

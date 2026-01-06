// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import static com.hedera.node.app.service.token.impl.schemas.V0710TokenSchema.STAKE_PERIOD_TIME_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.token.StakePeriodTime;
import com.hedera.node.app.service.token.impl.WritableStakePeriodTimeStore;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WritableStakePeriodTimeStoreTest {

    @Mock
    private WritableStates states;

    @Mock
    private WritableSingletonState stakePeriodTimeState;

    private WritableStakePeriodTimeStore subject;

    @BeforeEach
    void setUp() {
        given(states.getSingleton(STAKE_PERIOD_TIME_STATE_ID)).willReturn(stakePeriodTimeState);
        subject = new WritableStakePeriodTimeStore(states);
    }

    @Test
    void testPutPersistsStakePeriodTime() {
        final var stakePeriodTime = StakePeriodTime.newBuilder()
                .lastStakePeriodUpdateTime(
                        Timestamp.newBuilder().seconds(1000L).nanos(500).build())
                .build();

        subject.put(stakePeriodTime);

        verify(stakePeriodTimeState).put(stakePeriodTime);
    }

    @Test
    void testPutWithNullThrowsNPE() {
        assertThrows(NullPointerException.class, () -> subject.put(null));
    }

    @Test
    void testUpdateLastStakePeriodUpdateTime() {
        final var timestamp = Timestamp.newBuilder().seconds(2000L).nanos(100).build();

        subject.updateLastStakePeriodUpdateTime(timestamp);

        final var captor = ArgumentCaptor.forClass(StakePeriodTime.class);
        verify(stakePeriodTimeState).put(captor.capture());

        final var captured = captor.getValue();
        assertNotNull(captured);
        assertEquals(2000L, captured.lastStakePeriodUpdateTime().seconds());
        assertEquals(100, captured.lastStakePeriodUpdateTime().nanos());
    }

    @Test
    void testUpdateLastStakePeriodUpdateTimeWithNullThrowsNPE() {
        assertThrows(NullPointerException.class, () -> subject.updateLastStakePeriodUpdateTime(null));
    }

    @Test
    void testConstructorWithNullStatesThrows() {
        assertThrows(NullPointerException.class, () -> new WritableStakePeriodTimeStore(null));
    }

    @Test
    void testGetInheritedFromReadable() {
        final var stakePeriodTime = StakePeriodTime.newBuilder()
                .lastStakePeriodUpdateTime(
                        Timestamp.newBuilder().seconds(3000L).nanos(200).build())
                .build();
        given(stakePeriodTimeState.get()).willReturn(stakePeriodTime);

        final var result = subject.get();

        assertNotNull(result);
        assertEquals(3000L, result.lastStakePeriodUpdateTime().seconds());
        assertEquals(200, result.lastStakePeriodUpdateTime().nanos());
    }

    @Test
    void testPutDefaultStakePeriodTime() {
        subject.put(StakePeriodTime.DEFAULT);
        verify(stakePeriodTimeState).put(StakePeriodTime.DEFAULT);
    }

    @Test
    void testUpdateWithZeroTimestamp() {
        final var timestamp = Timestamp.newBuilder().seconds(0L).nanos(0).build();

        subject.updateLastStakePeriodUpdateTime(timestamp);

        final var captor = ArgumentCaptor.forClass(StakePeriodTime.class);
        verify(stakePeriodTimeState).put(captor.capture());

        final var captured = captor.getValue();
        assertNotNull(captured);
        assertEquals(0L, captured.lastStakePeriodUpdateTime().seconds());
        assertEquals(0, captured.lastStakePeriodUpdateTime().nanos());
    }
}

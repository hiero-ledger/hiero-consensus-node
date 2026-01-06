// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import static com.hedera.node.app.service.token.impl.schemas.V0710TokenSchema.STAKE_PERIOD_TIME_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.token.StakePeriodTime;
import com.hedera.node.app.service.token.impl.ReadableStakePeriodTimeStoreImpl;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadableStakePeriodTimeStoreImplTest {

    @Mock
    private ReadableStates states;

    @Mock
    private ReadableSingletonState stakePeriodTimeState;

    private ReadableStakePeriodTimeStoreImpl subject;

    @BeforeEach
    void setUp() {
        given(states.getSingleton(STAKE_PERIOD_TIME_STATE_ID)).willReturn(stakePeriodTimeState);
        subject = new ReadableStakePeriodTimeStoreImpl(states);
    }

    @Test
    void testGetReturnsStakePeriodTime() {
        final var stakePeriodTime = StakePeriodTime.newBuilder()
                .lastStakePeriodUpdateTime(
                        Timestamp.newBuilder().seconds(1000L).nanos(500).build())
                .build();
        given(stakePeriodTimeState.get()).willReturn(stakePeriodTime);

        final var result = subject.get();

        assertNotNull(result);
        assertEquals(1000L, result.lastStakePeriodUpdateTime().seconds());
        assertEquals(500, result.lastStakePeriodUpdateTime().nanos());
    }

    @Test
    void testGetReturnsDefaultStakePeriodTime() {
        given(stakePeriodTimeState.get()).willReturn(StakePeriodTime.DEFAULT);

        final var result = subject.get();

        assertNotNull(result);
        assertEquals(StakePeriodTime.DEFAULT, result);
    }

    @Test
    void testGetWithDifferentTimestampValues() {
        final var stakePeriodTime = StakePeriodTime.newBuilder()
                .lastStakePeriodUpdateTime(
                        Timestamp.newBuilder().seconds(5000L).nanos(999).build())
                .build();
        given(stakePeriodTimeState.get()).willReturn(stakePeriodTime);

        final var result = subject.get();

        assertNotNull(result);
        assertEquals(5000L, result.lastStakePeriodUpdateTime().seconds());
        assertEquals(999, result.lastStakePeriodUpdateTime().nanos());
    }

    @Test
    void testConstructorWithNullStatesThrows() {
        assertThrows(NullPointerException.class, () -> new ReadableStakePeriodTimeStoreImpl(null));
    }

    @Test
    void testGetThrowsWhenStateReturnsNull() {
        given(stakePeriodTimeState.get()).willReturn(null);
        assertThrows(NullPointerException.class, () -> subject.get());
    }
}


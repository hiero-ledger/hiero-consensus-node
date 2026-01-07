// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import static com.hedera.node.app.service.token.impl.schemas.V0700TokenSchema.STAKE_PERIOD_INFO_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.token.StakePeriodInfo;
import com.hedera.node.app.service.token.impl.ReadableStakePeriodInfoStoreImpl;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadableStakePeriodInfoStoreImplTest {

    @Mock
    private ReadableStates states;

    @Mock
    private ReadableSingletonState stakePeriodTimeState;

    private ReadableStakePeriodInfoStoreImpl subject;

    @BeforeEach
    void setUp() {
        given(states.getSingleton(STAKE_PERIOD_INFO_STATE_ID)).willReturn(stakePeriodTimeState);
        subject = new ReadableStakePeriodInfoStoreImpl(states);
    }

    @Test
    void testGetReturnsStakePeriodInfo() {
        final var stakePeriodTime = StakePeriodInfo.newBuilder()
                .lastStakePeriodCalculationTime(
                        Timestamp.newBuilder().seconds(1000L).nanos(500).build())
                .build();
        given(stakePeriodTimeState.get()).willReturn(stakePeriodTime);

        final var result = subject.get();

        assertNotNull(result);
        assertEquals(1000L, result.lastStakePeriodCalculationTime().seconds());
        assertEquals(500, result.lastStakePeriodCalculationTime().nanos());
    }

    @Test
    void testGetReturnsDefaultStakePeriodInfo() {
        given(stakePeriodTimeState.get()).willReturn(StakePeriodInfo.DEFAULT);

        final var result = subject.get();

        assertNotNull(result);
        assertEquals(StakePeriodInfo.DEFAULT, result);
    }

    @Test
    void testGetWithDifferentTimestampValues() {
        final var stakePeriodTime = StakePeriodInfo.newBuilder()
                .lastStakePeriodCalculationTime(
                        Timestamp.newBuilder().seconds(5000L).nanos(999).build())
                .build();
        given(stakePeriodTimeState.get()).willReturn(stakePeriodTime);

        final var result = subject.get();

        assertNotNull(result);
        assertEquals(5000L, result.lastStakePeriodCalculationTime().seconds());
        assertEquals(999, result.lastStakePeriodCalculationTime().nanos());
    }

    @Test
    void testConstructorWithNullStatesThrows() {
        assertThrows(NullPointerException.class, () -> new ReadableStakePeriodInfoStoreImpl(null));
    }

    @Test
    void testGetThrowsWhenStateReturnsNull() {
        given(stakePeriodTimeState.get()).willReturn(null);
        assertThrows(NullPointerException.class, () -> subject.get());
    }
}

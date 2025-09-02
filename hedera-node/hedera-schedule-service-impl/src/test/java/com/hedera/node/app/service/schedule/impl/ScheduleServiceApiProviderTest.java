// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SCHEDULE_EXPIRATION_TIME_TOO_FAR_IN_FUTURE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.schedule.impl.handlers.ScheduleCreateHandler;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.hedera.node.app.spi.throttle.Throttle;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.SchedulingConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableStates;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceApiProviderTest {
    private static final long EXPIRY = 123_456_789L;
    private static final long GAS_LIMIT = 456L;
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(EXPIRY - 1);
    private static final TransactionBody SYNTH_BODY = TransactionBody.newBuilder()
            .contractCall(ContractCallTransactionBody.newBuilder().gas(GAS_LIMIT))
            .build();
    private static final Configuration DEFAULT_CONFIG = HederaTestConfigBuilder.createConfig();
    private static final LedgerConfig LEDGER_CONFIG = DEFAULT_CONFIG.getConfigData(LedgerConfig.class);
    private static final SchedulingConfig SCHEDULING_CONFIG = DEFAULT_CONFIG.getConfigData(SchedulingConfig.class);

    @Mock
    private ScheduleCreateHandler scheduleCreateHandler;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableEntityCounters writableEntityCounters;

    @Mock
    private Throttle throttle;

    private ScheduleServiceApiProvider subject;

    @BeforeEach
    void setUp() {
        subject = new ScheduleServiceApiProvider(scheduleCreateHandler);
    }

    @Test
    void basicsAsExpected() {
        assertEquals(ScheduleService.NAME, subject.serviceName());
        assertInstanceOf(
                ScheduleServiceApiProvider.ScheduleServiceApiImpl.class,
                subject.newInstance(DEFAULT_CONFIG, writableStates, writableEntityCounters));
    }

    @Test
    void invalidExpirySecondNeverHasCapacity() {
        final var impl = subject.newInstance(DEFAULT_CONFIG, writableStates, writableEntityCounters);

        given(scheduleCreateHandler.checkExpiry(CONSENSUS_NOW, EXPIRY, LEDGER_CONFIG, SCHEDULING_CONFIG))
                .willReturn(SCHEDULE_EXPIRATION_TIME_TOO_FAR_IN_FUTURE);

        assertFalse(impl.hasContractCallCapacity(EXPIRY, CONSENSUS_NOW, GAS_LIMIT, AccountID.DEFAULT));
    }

    @Test
    void emptyThrottleImpliesNoCapacity() {
        final var impl = subject.newInstance(DEFAULT_CONFIG, writableStates, writableEntityCounters);

        assertFalse(impl.hasContractCallCapacity(EXPIRY, CONSENSUS_NOW, GAS_LIMIT, AccountID.DEFAULT));
    }

    @Test
    void hasCapacityIfThrottleAllows() {
        final var impl = subject.newInstance(DEFAULT_CONFIG, writableStates, writableEntityCounters);

        given(scheduleCreateHandler.checkExpiry(CONSENSUS_NOW, EXPIRY, LEDGER_CONFIG, SCHEDULING_CONFIG))
                .willReturn(OK);
        given(scheduleCreateHandler.loadThrottle(
                        any(), eq(DEFAULT_CONFIG.getConfigData(SchedulingConfig.class)), eq(EXPIRY)))
                .willReturn(Optional.of(throttle));
        given(throttle.allow(AccountID.DEFAULT, SYNTH_BODY, CONTRACT_CALL, Instant.ofEpochSecond(EXPIRY)))
                .willReturn(true);

        assertTrue(impl.hasContractCallCapacity(EXPIRY, CONSENSUS_NOW, GAS_LIMIT, AccountID.DEFAULT));
    }

    @Test
    void doesNotHaveCapacityIfThrottleAllows() {
        final var impl = subject.newInstance(DEFAULT_CONFIG, writableStates, writableEntityCounters);

        given(scheduleCreateHandler.checkExpiry(CONSENSUS_NOW, EXPIRY, LEDGER_CONFIG, SCHEDULING_CONFIG))
                .willReturn(OK);
        given(scheduleCreateHandler.loadThrottle(
                        any(), eq(DEFAULT_CONFIG.getConfigData(SchedulingConfig.class)), eq(EXPIRY)))
                .willReturn(Optional.of(throttle));
        given(throttle.allow(AccountID.DEFAULT, SYNTH_BODY, CONTRACT_CALL, Instant.ofEpochSecond(EXPIRY)))
                .willReturn(false);

        assertFalse(impl.hasContractCallCapacity(EXPIRY, CONSENSUS_NOW, GAS_LIMIT, AccountID.DEFAULT));
    }
}

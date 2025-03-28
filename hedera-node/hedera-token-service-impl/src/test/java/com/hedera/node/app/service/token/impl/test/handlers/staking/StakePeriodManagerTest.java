// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.staking;

import static com.hedera.node.app.service.token.impl.handlers.staking.StakePeriodManager.ZONE_UTC;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.node.app.service.token.ReadableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.impl.ReadableNetworkStakingRewardsStoreImpl;
import com.hedera.node.app.service.token.impl.handlers.staking.StakePeriodManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.spi.WritableStates;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StakePeriodManagerTest {
    @Mock
    private ConfigProvider configProvider;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private WritableStates states;

    private final InstantSource instantSource = InstantSource.system();

    private StakePeriodManager subject;
    private ReadableNetworkStakingRewardsStore stakingRewardsStore;

    private static final VersionedConfiguration versionConfig =
            new VersionedConfigImpl(HederaTestConfigBuilder.createConfig(), 1);

    @BeforeEach
    public void setUp() {
        given(configProvider.getConfiguration()).willReturn(versionConfig);
        subject = new StakePeriodManager(configProvider, instantSource);
    }

    @Test
    void canSetStakePeriod() {
        final var firstDay = Instant.ofEpochSecond(0);
        final var secondDay = Instant.ofEpochSecond(86_401);
        assertEquals(0, subject.currentStakePeriod());
        subject.setCurrentStakePeriodFor(firstDay);
        assertEquals(0, subject.currentStakePeriod());
        subject.setCurrentStakePeriodFor(secondDay);
        assertEquals(1, subject.currentStakePeriod());
    }

    @Test
    void stakePeriodStartForProdIsMidnightUtcCalendarDay() {
        givenStakingRewardsActivated();

        final var then = Instant.parse("2021-06-07T23:59:58.369613Z");
        final var dateThen = LocalDate.ofInstant(then, ZONE_UTC);
        final var prodPeriod = dateThen.toEpochDay();
        final var midnight = dateThen.atStartOfDay().toEpochSecond(ZoneOffset.UTC);

        assertEquals(midnight, subject.epochSecondAtStartOfPeriod(prodPeriod));
    }

    @Test
    void stakePeriodStartForDevEnvIsPeriodTimesSeconds() {
        givenStakingRewardsActivated();
        givenStakePeriodMins(2);
        subject = new StakePeriodManager(configProvider, instantSource);

        final var somePeriod = 1_234_567L;

        assertEquals(somePeriod * 2 * 60L, subject.epochSecondAtStartOfPeriod(somePeriod));
    }

    @Test
    void givesCurrentStakePeriodAsExpected() {
        givenStakingRewardsNotActivated();

        final var consensusNow = Instant.ofEpochSecond(12345L);
        final var period = subject.currentStakePeriod();
        final var expectedPeriod = LocalDate.ofInstant(consensusNow, ZONE_UTC).toEpochDay();
        assertEquals(expectedPeriod, period);
        // When staking rewards are not activated the estimated non-rewardable is Long.MIN_VALUE
        var firstNonRewardable = subject.firstNonRewardableStakePeriod(stakingRewardsStore);
        assertEquals(Long.MIN_VALUE, firstNonRewardable);

        // when staking rewards are activated the estimated non-rewardable is the current period minus one
        givenStakingRewardsActivated();
        firstNonRewardable = subject.firstNonRewardableStakePeriod(stakingRewardsStore);
        assertEquals(expectedPeriod - 1, firstNonRewardable);
    }

    @Test
    void estimatesBasedOnWallClockTimeForDevProperty() {
        givenStakingRewardsNotActivated();
        givenStakePeriodMins(1);
        subject = new StakePeriodManager(configProvider, instantSource);
        // When staking rewards are not activated the estimated period is Long.MIN_VALUE
        final var approx = Instant.now().getEpochSecond() / 60;
        assertTrue(Math.abs(approx - subject.estimatedCurrentStakePeriod()) <= 1);
        assertEquals(Long.MIN_VALUE, subject.estimatedFirstNonRewardableStakePeriod(stakingRewardsStore));

        // Once rewards are activated the estimated period is the current period minus one
        givenStakingRewardsActivated();
        assertEquals(approx - 1, subject.estimatedFirstNonRewardableStakePeriod(stakingRewardsStore));

        assertFalse(subject.isEstimatedRewardable(-1, stakingRewardsStore));
        assertFalse(subject.isEstimatedRewardable(approx - 1, stakingRewardsStore));
        assertTrue(subject.isEstimatedRewardable(approx - 2, stakingRewardsStore));
    }

    @Test
    void estimatesBasedOnInstantNowForProdSetting() {
        final var expected = LocalDate.ofInstant(Instant.now(), ZONE_UTC).toEpochDay();
        assertEquals(expected, subject.estimatedCurrentStakePeriod());
    }

    @Test
    void calculatesIfRewardShouldBeGiven() {
        givenStakingRewardsActivated();

        final var consensusNow = Instant.ofEpochSecond(123456789L);
        subject.setCurrentStakePeriodFor(consensusNow);
        final var todayNumber = subject.currentStakePeriod() - 1;

        // stakePeriodStart should be positive and within last 366 days
        var stakePeriodStart = todayNumber - 366;
        assertTrue(subject.isRewardable(stakePeriodStart, stakingRewardsStore));

        // stakePeriodStart should be positive
        stakePeriodStart = -1;
        assertFalse(subject.isRewardable(stakePeriodStart, stakingRewardsStore));

        // stakePeriodStart should be positive and within last 366 days
        stakePeriodStart = todayNumber - 365;
        assertTrue(subject.isRewardable(stakePeriodStart, stakingRewardsStore));

        // stakePeriodStart should be positive and within last 366 days
        stakePeriodStart = todayNumber - 1;
        assertTrue(subject.isRewardable(stakePeriodStart, stakingRewardsStore));

        // stakePeriodStart should be positive and within last 366 days
        stakePeriodStart = todayNumber - 2;
        assertTrue(subject.isRewardable(stakePeriodStart, stakingRewardsStore));
    }

    @Test
    void validatesIfStartPeriodIsWithinRange() {
        givenStakingRewardsActivated();

        final var consensusNow = Instant.ofEpochSecond(12345678910L);
        subject.setCurrentStakePeriodFor(consensusNow);
        final long stakePeriodStart = subject.currentStakePeriod();

        assertTrue(subject.isRewardable(stakePeriodStart - 365, stakingRewardsStore));
        assertFalse(subject.isRewardable(-1, stakingRewardsStore));
        assertFalse(subject.isRewardable(stakePeriodStart, stakingRewardsStore));
    }

    @Test
    void givesEffectivePeriodCorrectly() {
        final var delta = 500;
        final var consensusNow = Instant.ofEpochSecond(12345678910L);
        subject.setCurrentStakePeriodFor(consensusNow);

        final var stakePeriod = subject.currentStakePeriod();
        final var period = subject.effectivePeriod(stakePeriod - delta);

        final var expectedEffectivePeriod = LocalDate.ofInstant(Instant.ofEpochSecond(12345678910L), ZONE_UTC)
                .toEpochDay();
        assertEquals(expectedEffectivePeriod - 366, period);
        assertEquals(expectedEffectivePeriod - 10, subject.effectivePeriod(stakePeriod - 10));
    }

    @Test
    void calculatesCurrentStakingPeriodForCustomStakingPeriodProperty() {
        givenStakePeriodMins(2880);
        subject = new StakePeriodManager(configProvider, instantSource);

        final var consensusNow = Instant.ofEpochSecond(12345L);
        final var expectedPeriod = LocalDate.ofInstant(consensusNow, ZONE_UTC).toEpochDay() / 2;
        subject.setCurrentStakePeriodFor(consensusNow);
        final var period = subject.currentStakePeriod();
        assertEquals(expectedPeriod, period);

        // Use a different staking period
        givenStakePeriodMins(10);
        subject = new StakePeriodManager(configProvider, instantSource);
        final var diffConsensusNow = consensusNow.plusSeconds(12345L);
        subject.setCurrentStakePeriodFor(diffConsensusNow);
        assertEquals(41L, subject.currentStakePeriod());
    }

    private void givenStakingRewardsActivated() {
        final AtomicReference<NetworkStakingRewards> backingValue =
                new AtomicReference<>(new NetworkStakingRewards(true, 1L, 2L, 3L, Timestamp.DEFAULT));
        final var stakingRewardsState =
                new WritableSingletonStateBase<>(STAKING_NETWORK_REWARDS_KEY, backingValue::get, backingValue::set);
        given(states.getSingleton(STAKING_NETWORK_REWARDS_KEY))
                .willReturn((WritableSingletonState) stakingRewardsState);
        stakingRewardsStore = new ReadableNetworkStakingRewardsStoreImpl(states);
    }

    private void givenStakingRewardsNotActivated() {
        final AtomicReference<NetworkStakingRewards> backingValue =
                new AtomicReference<>(new NetworkStakingRewards(false, 1L, 2L, 3L, Timestamp.DEFAULT));
        final var stakingRewardsState =
                new WritableSingletonStateBase<>(STAKING_NETWORK_REWARDS_KEY, backingValue::get, backingValue::set);
        given(states.getSingleton(STAKING_NETWORK_REWARDS_KEY))
                .willReturn((WritableSingletonState) stakingRewardsState);
        stakingRewardsStore = new ReadableNetworkStakingRewardsStoreImpl(states);
    }

    private void givenStakePeriodMins(final int value) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("staking.periodMins", String.valueOf(value))
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
    }
}

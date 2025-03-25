// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.tipset;

import static com.swirlds.common.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.event.creation.EventCreator;
import com.swirlds.platform.event.creation.rules.PlatformStatusRule;
import com.swirlds.platform.pool.TransactionPoolNexus;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.event.creator.EventCreationRule;
import org.hiero.event.creator.impl.EventCreationConfig_;
import org.hiero.event.creator.impl.rules.AggregateEventCreationRules;
import org.hiero.event.creator.impl.rules.MaximumRateRule;
import org.hiero.event.creator.impl.rules.PlatformHealthRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Tipset Event Creation Rules Tests")
class EventCreationRulesTests {

    @Test
    void emptyAggregateTest() {
        final EventCreationRule rule = AggregateEventCreationRules.of(List.of());
        assertTrue(rule.isEventCreationPermitted());

        // should not throw
        rule.eventWasCreated();
    }

    @Test
    void aggregateTest() {
        final EventCreationRule rule1 = mock(EventCreationRule.class);
        when(rule1.isEventCreationPermitted()).thenAnswer(invocation -> true);
        final AtomicInteger rule1Count = new AtomicInteger(0);
        doAnswer(invocation -> {
                    rule1Count.incrementAndGet();
                    return null;
                })
                .when(rule1)
                .eventWasCreated();

        final EventCreationRule rule2 = mock(EventCreationRule.class);
        when(rule2.isEventCreationPermitted()).thenAnswer(invocation -> true);
        final AtomicInteger rule2Count = new AtomicInteger(0);
        doAnswer(invocation -> {
                    rule2Count.incrementAndGet();
                    return null;
                })
                .when(rule2)
                .eventWasCreated();

        final EventCreationRule rule3 = mock(EventCreationRule.class);
        when(rule3.isEventCreationPermitted()).thenAnswer(invocation -> true);
        final AtomicInteger rule3Count = new AtomicInteger(0);
        doAnswer(invocation -> {
                    rule3Count.incrementAndGet();
                    return null;
                })
                .when(rule3)
                .eventWasCreated();

        final EventCreationRule rule4 = mock(EventCreationRule.class);
        when(rule4.isEventCreationPermitted()).thenAnswer(invocation -> true);
        final AtomicInteger rule4Count = new AtomicInteger(0);
        doAnswer(invocation -> {
                    rule4Count.incrementAndGet();
                    return null;
                })
                .when(rule4)
                .eventWasCreated();

        final EventCreationRule aggregateRule = AggregateEventCreationRules.of(List.of(rule1, rule2, rule3, rule4));

        assertTrue(aggregateRule.isEventCreationPermitted());

        when(rule3.isEventCreationPermitted()).thenAnswer(invocation -> false);
        assertFalse(aggregateRule.isEventCreationPermitted());

        when(rule2.isEventCreationPermitted()).thenAnswer(invocation -> false);
        assertFalse(aggregateRule.isEventCreationPermitted());

        when(rule1.isEventCreationPermitted()).thenAnswer(invocation -> false);
        assertFalse(aggregateRule.isEventCreationPermitted());

        when(rule4.isEventCreationPermitted()).thenAnswer(invocation -> false);
        assertFalse(aggregateRule.isEventCreationPermitted());

        aggregateRule.eventWasCreated();
        assertEquals(1, rule1Count.get());
        assertEquals(1, rule2Count.get());
        assertEquals(1, rule3Count.get());
        assertEquals(1, rule4Count.get());
    }

    @Test
    void blockedByFreeze() {
        final Supplier<PlatformStatus> platformStatusSupplier = () -> FREEZING;

        final AtomicInteger numSignatureTransactions = new AtomicInteger(0);
        final TransactionPoolNexus transactionPoolNexus = mock(TransactionPoolNexus.class);
        when(transactionPoolNexus.hasBufferedSignatureTransactions())
                .thenAnswer(invocation -> numSignatureTransactions.get() > 0);

        final AtomicInteger eventCreationCount = new AtomicInteger(0);
        final EventCreator baseEventCreator = mock(EventCreator.class);
        when(baseEventCreator.maybeCreateEvent()).thenAnswer(invocation -> {
            eventCreationCount.incrementAndGet();
            return null;
        });

        final EventCreationRule rule = new PlatformStatusRule(platformStatusSupplier, transactionPoolNexus);

        assertFalse(rule.isEventCreationPermitted());
        numSignatureTransactions.set(1);
        assertTrue(rule.isEventCreationPermitted());
    }

    @Test
    void blockedByStatus() {
        final TransactionPoolNexus transactionPoolNexus = mock(TransactionPoolNexus.class);

        final AtomicReference<PlatformStatus> status = new AtomicReference<>();

        final AtomicInteger eventCreationCount = new AtomicInteger(0);
        final EventCreator baseEventCreator = mock(EventCreator.class);
        when(baseEventCreator.maybeCreateEvent()).thenAnswer(invocation -> {
            eventCreationCount.incrementAndGet();
            return null;
        });

        final EventCreationRule rule = new PlatformStatusRule(status::get, transactionPoolNexus);

        for (final PlatformStatus platformStatus : PlatformStatus.values()) {
            if (platformStatus == FREEZING) {
                // this is checked in another test, don't bother checking
                continue;
            }

            status.set(platformStatus);

            if (platformStatus == ACTIVE || platformStatus == CHECKING) {
                assertTrue(rule.isEventCreationPermitted());
            } else {
                assertFalse(rule.isEventCreationPermitted());
            }
        }
    }

    @Test
    void noRateLimitTest() {
        final Time time = new FakeTime();
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().withTime(time).build();

        final AtomicInteger eventCreationCount = new AtomicInteger(0);
        final EventCreator baseEventCreator = mock(EventCreator.class);
        when(baseEventCreator.maybeCreateEvent()).thenAnswer(invocation -> {
            eventCreationCount.incrementAndGet();
            return mock(PlatformEvent.class);
        });

        final EventCreationRule rule = new MaximumRateRule(platformContext);

        // Ask for a bunch of events to be created without advancing the time.
        for (int i = 0; i < 100; i++) {
            assertTrue(rule.isEventCreationPermitted());
        }
    }

    @Test
    void rateLimitTest() {
        final Random random = getRandomPrintSeed();

        final int maxRate = 100;
        final Duration period = Duration.ofSeconds(1).dividedBy(maxRate);

        final Configuration configuration = new TestConfigBuilder()
                .withValue(EventCreationConfig_.MAX_CREATION_RATE, maxRate)
                .getOrCreateConfig();

        final FakeTime time = new FakeTime();
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .withTime(time)
                .build();

        final EventCreationRule rule = new MaximumRateRule(platformContext);

        int millisSinceLastEvent = (int) period.toMillis();
        for (int i = 0; i < 100; i++) {
            final boolean tickForwards = random.nextBoolean();
            if (tickForwards) {
                final int millisToTick = random.nextInt(5);
                time.tick(Duration.ofMillis(millisToTick));
                millisSinceLastEvent += millisToTick;
            }

            if (millisSinceLastEvent >= period.toMillis()) {
                assertTrue(rule.isEventCreationPermitted());

                // Sometimes create an event. Sometimes don't.
                if (random.nextBoolean()) {
                    rule.eventWasCreated();
                    millisSinceLastEvent = 0;
                }
            } else {
                assertFalse(rule.isEventCreationPermitted());
            }
        }
    }

    @Test
    void platformHealthRuleTest() {
        final AtomicReference<Duration> unhealthyDuration = new AtomicReference<>(Duration.ZERO);
        final EventCreationRule rule = new PlatformHealthRule(Duration.ofSeconds(5), unhealthyDuration::get);

        assertTrue(rule.isEventCreationPermitted());

        unhealthyDuration.set(Duration.ofSeconds(1));
        assertTrue(rule.isEventCreationPermitted());

        unhealthyDuration.set(Duration.ofSeconds(5));
        assertTrue(rule.isEventCreationPermitted());

        unhealthyDuration.set(Duration.ofSeconds(5, 1));
        assertFalse(rule.isEventCreationPermitted());

        unhealthyDuration.set(Duration.ofSeconds(50000000));
        assertFalse(rule.isEventCreationPermitted());

        unhealthyDuration.set(Duration.ofSeconds(5));
        assertTrue(rule.isEventCreationPermitted());
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.throttles;

import static com.hedera.node.app.hapi.utils.throttles.BucketThrottle.CAPACITY_UNITS_PER_NANO_TXN;
import static com.hedera.node.app.hapi.utils.throttles.BucketThrottle.CAPACITY_UNITS_PER_TXN;
import static com.hedera.node.app.hapi.utils.throttles.BucketThrottle.MTPS_PER_TPS;
import static com.hedera.node.app.hapi.utils.throttles.BucketThrottle.NTPS_PER_MTPS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BucketThrottleTest {
    static final long NTPS_PER_TPS = 1_000_000_000L;

    @Test
    void factoryRejectsNeverPassableThrottle() {
        // expect:
        final var aMsg = assertThrows(
                        IllegalArgumentException.class, () -> BucketThrottle.withMtpsAndBurstPeriod(499, 2))
                .getMessage();
        final var bMsg = assertThrows(
                        IllegalArgumentException.class, () -> BucketThrottle.withMtpsAndBurstPeriod(Long.MAX_VALUE, 2))
                .getMessage();
        final var cMsg = assertThrows(
                        IllegalArgumentException.class, () -> BucketThrottle.withMtpsAndBurstPeriod(184467440L, 789))
                .getMessage();
        // and:
        assertEquals("A throttle with 499 MTPS and 2000ms burst period can never allow a transaction", aMsg);
        assertEquals("Base bucket capacity calculation outside numeric range", bMsg);
        assertEquals("Scaled bucket capacity calculation outside numeric range", cMsg);
    }

    @Test
    void factoriesResultInExpectedThrottles() {
        // setup:
        int tps = 1_000;
        long expectedCapacity = tps * NTPS_PER_TPS * CAPACITY_UNITS_PER_NANO_TXN;

        // given:
        var fromTps = BucketThrottle.withTps(tps);
        var fromMtps = BucketThrottle.withMtps(tps * MTPS_PER_TPS);
        var fromTpsAndBurstPeriod = BucketThrottle.withTpsAndBurstPeriod(tps / 2, 2);
        var fromTpsAndBurstPeriodMs = BucketThrottle.withTpsAndBurstPeriodMs(tps / 2, 2000);
        var fromMtpsAndBurstPeriod = BucketThrottle.withMtpsAndBurstPeriod(tps / 2 * MTPS_PER_TPS, 2);
        var fromMtpsAndBurstPeriodMs = BucketThrottle.withMtpsAndBurstPeriodMs(tps / 2 * MTPS_PER_TPS, 2000);

        // expect:
        assertEquals(expectedCapacity, fromTps.bucket().brimfulCapacity());
        assertEquals(expectedCapacity, fromMtps.bucket().brimfulCapacity());
        assertEquals(expectedCapacity, fromTpsAndBurstPeriod.bucket().brimfulCapacity());
        assertEquals(expectedCapacity, fromMtpsAndBurstPeriod.bucket().brimfulCapacity());
        assertEquals(expectedCapacity, fromTpsAndBurstPeriodMs.bucket().brimfulCapacity());
        assertEquals(expectedCapacity, fromMtpsAndBurstPeriodMs.bucket().brimfulCapacity());
        // and:
        assertEquals(tps * MTPS_PER_TPS, fromTps.mtps());
        assertEquals(tps * MTPS_PER_TPS, fromMtps.mtps());
        assertEquals(tps * MTPS_PER_TPS / 2, fromTpsAndBurstPeriod.mtps());
        assertEquals(tps * MTPS_PER_TPS / 2, fromMtpsAndBurstPeriod.mtps());
        assertEquals(tps * MTPS_PER_TPS / 2, fromTpsAndBurstPeriodMs.mtps());
        assertEquals(tps * MTPS_PER_TPS / 2, fromMtpsAndBurstPeriodMs.mtps());
    }

    @Test
    void rejectsUnsupportedNumOfTransactions() {
        // setup:
        int maxTps = (int) (Long.MAX_VALUE / CAPACITY_UNITS_PER_TXN);

        // given:
        var subject = BucketThrottle.withTps(maxTps);

        // when:
        var shouldAllowUnsupportedTxns = subject.allow(maxTps + 123, 0);

        // then:
        assertFalse(shouldAllowUnsupportedTxns);
    }

    @Test
    void withExcessElapsedNanosCompletelyEmptiesBucket() {
        // setup:
        int tps = 1_000;

        // given:
        var subject = BucketThrottle.withTps(tps);

        // when:
        var shouldAllowAll = subject.allow(1000, Long.MAX_VALUE / (tps * 1_000) + 123);

        // then:
        assertTrue(shouldAllowAll);
        assertEquals(0, subject.bucket().brimfulCapacityFree());
    }

    @Test
    void canAllowInstantaneously() {
        int tps = 1_000;
        long internalCapacity = tps * NTPS_PER_TPS * CAPACITY_UNITS_PER_NANO_TXN;

        var subject = BucketThrottle.withTps(tps);

        assertTrue(subject.allowInstantaneous(tps / 2));
        assertEquals(internalCapacity / 2, subject.bucket().brimfulCapacityFree());
    }

    @Test
    void withZeroElapsedNanosSimplyAdjustsCapacityFree() {
        // setup:
        int tps = 1_000;
        long internalCapacity = tps * NTPS_PER_TPS * CAPACITY_UNITS_PER_NANO_TXN;

        // given:
        var subject = BucketThrottle.withTps(tps);

        // when:
        var shouldAllowHalf = subject.allow(tps / 2, 0L);

        // then:
        assertTrue(shouldAllowHalf);
        assertEquals(internalCapacity / 2, subject.bucket().brimfulCapacityFree());
    }

    @Test
    void withZeroElapsedNanosRejectsUnavailableCapacity() {
        // setup:
        int tps = 1_000;
        long internalCapacity = tps * NTPS_PER_TPS * CAPACITY_UNITS_PER_NANO_TXN;

        // given:
        var subject = BucketThrottle.withTps(tps);

        // when:
        var shouldntAllowOverRate = subject.allow(tps + 1, 0L);

        // then:
        assertFalse(shouldntAllowOverRate);
        assertEquals(internalCapacity, subject.bucket().brimfulCapacityFree());
    }

    @Test
    void scalesLeakRateByDesiredTps() {
        // setup:
        int tps = 1_000;
        long internalCapacity = tps * NTPS_PER_TPS * CAPACITY_UNITS_PER_NANO_TXN;

        // given:
        var subject = BucketThrottle.withTps(tps);
        // and:
        subject.bucket().resetUsed(internalCapacity);

        // when:
        var shouldAllowWithJustEnoughCapacity = subject.allow(1, NTPS_PER_TPS / tps);

        // then:
        assertTrue(shouldAllowWithJustEnoughCapacity);
        assertEquals(0, subject.bucket().brimfulCapacityFree());
    }

    @Test
    void canLeakDirectly() {
        int tps = 1_000;
        long internalCapacity = tps * NTPS_PER_TPS * CAPACITY_UNITS_PER_NANO_TXN;

        var subject = BucketThrottle.withTps(tps);
        subject.bucket().resetUsed(internalCapacity);

        subject.leakFor(NTPS_PER_TPS / tps);

        final var expectedNewUsed = internalCapacity - NTPS_PER_TPS * CAPACITY_UNITS_PER_NANO_TXN;
        final var actualNewUsed = subject.bucket().capacityUsed();
        assertEquals(expectedNewUsed, actualNewUsed);
    }

    @Test
    void scalesLeakRateByDesiredMtps() {
        // setup:
        int mtps = 100;
        int burstPeriod = 10;
        long internalCapacity = mtps * NTPS_PER_MTPS * CAPACITY_UNITS_PER_NANO_TXN * burstPeriod;

        // given:
        var subject = BucketThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod);
        // and:
        subject.bucket().resetUsed(internalCapacity);

        // when:
        var shouldAllowWithJustEnoughCapacity = subject.allow(1, 1000 * NTPS_PER_TPS / mtps);

        // then:
        assertTrue(shouldAllowWithJustEnoughCapacity);
        assertEquals(0, subject.bucket().brimfulCapacityFree());
    }

    @Test
    void scalesLeakRateByDesiredTpsUnderOne() {
        // setup:
        int mtps = 500;
        int burstPeriod = 2;

        // given:
        var subject = BucketThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod);
        // and:
        subject.bucket().resetUsed(mtps);

        // when:
        var shouldAllowWithJustEnoughCapacity = subject.allow(1, 1);

        // then:
        assertTrue(shouldAllowWithJustEnoughCapacity);
        assertEquals(0, subject.bucket().brimfulCapacityFree());
    }

    @Test
    void hasExpectedPercentUsed() {
        int mtps = 500;
        int burstPeriod = 2;

        var subject = BucketThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod);
        final var totalCap = subject.bucket().brimfulCapacity();
        subject.bucket().useCapacity(totalCap / 2);

        assertEquals(50.0, subject.percentUsed(0));
        assertEquals(25.0, subject.percentUsed(totalCap / mtps / 4));
    }

    @Test
    void hasExpectedInstantaneousPercentUsed() {
        int mtps = 500;
        int burstPeriod = 2;

        var subject = BucketThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod);
        final var totalCap = subject.bucket().brimfulCapacity();
        subject.bucket().useCapacity(totalCap / 2);

        assertEquals(50.0, subject.instantaneousPercentUsed());
    }

    @Test
    void canReclaimCapacity() {
        // setup:
        int mtps = 500;
        int burstPeriod = 4;
        long internalCapacity = mtps * NTPS_PER_MTPS * burstPeriod * CAPACITY_UNITS_PER_NANO_TXN;

        // given:
        var subject = BucketThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod);

        // when:
        var firstDecision = subject.allow(1, 0L);
        var secondDecision = subject.allow(1, 0L);
        var thirdDecision = subject.allow(1, 0L);

        // then:
        assertTrue(firstDecision);
        assertTrue(secondDecision);
        assertFalse(thirdDecision);
        // and when:
        subject.reclaimLastAllowedUse();
        // then:
        assertEquals(internalCapacity, subject.bucket().brimfulCapacityFree());
    }

    @Test
    void canResetReclaimCapacity() {
        // setup:
        int mtps = 500;
        int burstPeriod = 4;

        // given:
        var subject = BucketThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod);

        // when:
        var firstDecision = subject.allow(1, 0L);
        var secondDecision = subject.allow(1, 0L);
        var thirdDecision = subject.allow(1, 0L);

        // then:
        assertTrue(firstDecision);
        assertTrue(secondDecision);
        assertFalse(thirdDecision);
        // and when:
        subject.resetLastAllowedUse();
        subject.reclaimLastAllowedUse();

        // then:
        assertEquals(0, subject.bucket().brimfulCapacityFree());
    }

    @Test
    void hasExpectedMtps() {
        // setup:
        int mtps = 250;
        int burstPeriod = 4;
        // and:
        int numAllowed = 0;
        int numSeconds = 100;
        long numNanoseconds = numSeconds * NTPS_PER_TPS;

        // given:
        var subject = BucketThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod);

        for (long i = 0; i < numNanoseconds / 1000; i++) {
            if (subject.allow(1, 1_000L)) {
                numAllowed++;
            }
        }

        // then:
        assertEquals(numAllowed, (numSeconds * mtps) / 1000);
    }

    @Test
    void hasExpectedTps() {
        // setup:
        int tps = 250;
        // and:
        int numAllowed = 0;
        int numSeconds = 100;
        int perDecision = 10;
        long numNanoseconds = numSeconds * NTPS_PER_TPS;

        // given:
        var subject = BucketThrottle.withTps(tps);

        long decisionPeriod = 1000;
        for (long i = 0; i < numNanoseconds / decisionPeriod; i++) {
            if (subject.allow(perDecision, decisionPeriod)) {
                numAllowed += perDecision;
            }
        }

        // then:
        double actual = 1.0 * numAllowed;
        double epsilon = 0.01;
        double expected = 1.0 * numSeconds * tps;
        assertEquals(1.0, actual / expected, epsilon);
    }
}

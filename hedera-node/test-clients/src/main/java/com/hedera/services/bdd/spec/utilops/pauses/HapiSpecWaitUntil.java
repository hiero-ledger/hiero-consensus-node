// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.pauses;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.doIfNotInterrupted;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.swirlds.common.stream.LinkedObjectStreamUtilities.getPeriod;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiSpecWaitUntil extends UtilOp {
    static final Logger log = LogManager.getLogger(HapiSpecWaitUntil.class);

    private long timeMs;
    private long stakePeriodMins;
    private long targetTimeOffsetSecs;
    private long adhocPeriodMs;
    private boolean backgroundTraffic = false;
    private WaitUntilTarget waitUntilTarget;

    enum WaitUntilTarget {
        SPECIFIC_TIME,
        START_OF_NEXT_STAKING_PERIOD,
        START_OF_NEXT_ADHOC_PERIOD
    }

    public HapiSpecWaitUntil(String timeOfDay) throws ParseException {
        timeMs = convertToEpochMillis(timeOfDay);
        waitUntilTarget = WaitUntilTarget.SPECIFIC_TIME;
    }

    public HapiSpecWaitUntil withBackgroundTraffic() {
        backgroundTraffic = true;
        return this;
    }

    public static HapiSpecWaitUntil untilStartOfNextAdhocPeriod(final long adhocPeriodMs) {
        return new HapiSpecWaitUntil(adhocPeriodMs);
    }

    public static HapiSpecWaitUntil untilStartOfNextStakingPeriod(final long stakePeriodMins) {
        return new HapiSpecWaitUntil(stakePeriodMins, 0L);
    }

    public static HapiSpecWaitUntil untilJustBeforeStakingPeriod(final long stakePeriodMins, final long secondsBefore) {
        return new HapiSpecWaitUntil(stakePeriodMins, -secondsBefore);
    }

    private HapiSpecWaitUntil(final long adhocPeriodMs) {
        this.adhocPeriodMs = adhocPeriodMs;
        this.waitUntilTarget = WaitUntilTarget.START_OF_NEXT_ADHOC_PERIOD;
    }

    private HapiSpecWaitUntil(final long stakePeriodMins, final long targetTimeOffsetSecs) {
        this.stakePeriodMins = stakePeriodMins;
        this.targetTimeOffsetSecs = targetTimeOffsetSecs;
        this.waitUntilTarget = WaitUntilTarget.START_OF_NEXT_STAKING_PERIOD;
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var now = spec.consensusTime();
        final var done = new AtomicBoolean();
        final CompletableFuture<Void> maybeTraffic = backgroundTraffic
                ? CompletableFuture.runAsync(() -> {
                    while (!done.get()) {
                        allRunFor(
                                spec,
                                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1))
                                        .deferStatusResolution()
                                        .noLogging()
                                        .hasAnyStatusAtAll());
                        doIfNotInterrupted(() -> spec.sleepConsensusTime(Duration.ofSeconds(1)));
                    }
                })
                : CompletableFuture.completedFuture(null);
        if (waitUntilTarget == WaitUntilTarget.START_OF_NEXT_STAKING_PERIOD) {
            final var stakePeriodMillis = stakePeriodMins * 60 * 1000L;
            final var currentPeriod = getPeriod(now, stakePeriodMillis);
            final var nextPeriod = currentPeriod + 1;
            timeMs = nextPeriod * stakePeriodMillis + (targetTimeOffsetSecs * 1000L);
        } else if (waitUntilTarget == WaitUntilTarget.START_OF_NEXT_ADHOC_PERIOD) {
            final var currentPeriod = getPeriod(now, adhocPeriodMs);
            final var nextPeriod = currentPeriod + 1;
            timeMs = nextPeriod * adhocPeriodMs;
        }
        log.info(
                "Sleeping until epoch milli {} ({} CST)",
                timeMs,
                Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()));
        spec.sleepConsensusTime(Duration.ofMillis(timeMs - now.toEpochMilli()));
        done.set(true);
        maybeTraffic.join();
        return false;
    }

    private long convertToEpochMillis(final String timeOfDay) throws ParseException {
        SimpleDateFormat dateMonthYear = new SimpleDateFormat("MM/dd/yyyy");
        SimpleDateFormat dateMonthYearTime = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");
        Date currentDate = new Date();
        String currDateInString = dateMonthYear.format(currentDate);

        String currDateTimeInString = currDateInString + " " + timeOfDay;

        return dateMonthYearTime.parse(currDateTimeInString).toInstant().toEpochMilli();
    }
}

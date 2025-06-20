// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import static com.google.common.base.Stopwatch.createStarted;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.base.Stopwatch;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RunLoadTest extends UtilOp {
    private static final Logger log = LogManager.getLogger(RunLoadTest.class);
    private static final int DEFAULT_TPS_TARGET = 500;
    private static final long DEFAULT_DURATION = 30;
    private static final TimeUnit DEFAULT_DURATION_UNIT = TimeUnit.SECONDS;
    private static final int DEFAULT_THREADS = 1;

    private DoubleSupplier targetTps = () -> DEFAULT_TPS_TARGET;
    private LongSupplier testDuration = () -> DEFAULT_DURATION;
    private Supplier<TimeUnit> ofUnit = () -> DEFAULT_DURATION_UNIT;
    private IntSupplier threads = () -> DEFAULT_THREADS;

    private final Supplier<HapiSpecOperation[]> opSource;

    private AtomicLong totalOpsAllThread = new AtomicLong();

    public RunLoadTest tps(DoubleSupplier targetTps) {
        this.targetTps = targetTps;
        return this;
    }

    public RunLoadTest setNumberOfThreads(IntSupplier numberOfThreads) {
        this.threads = numberOfThreads;
        return this;
    }

    public RunLoadTest lasting(LongSupplier duration, Supplier<TimeUnit> ofUnit) {
        this.testDuration = duration;
        this.ofUnit = ofUnit;
        return this;
    }

    public RunLoadTest(Supplier<HapiSpecOperation[]> opSource) {
        this.opSource = opSource;
    }

    @Override
    protected boolean submitOp(HapiSpec spec) {
        return threadMode(spec);
    }

    protected boolean threadMode(HapiSpec spec) {
        int numberOfThreads = threads.getAsInt();
        Thread[] threadClients = new Thread[numberOfThreads];

        // Dynamically instantiate test case thread and pass arguments to it
        for (int k = 0; k < numberOfThreads; k++) {
            threadClients[k] = new Thread(() -> testRun(spec));
            threadClients[k].setName("thread" + k);
        }

        for (int k = 0; k < numberOfThreads; k++) {
            threadClients[k].start();
        }
        for (int k = 0; k < numberOfThreads; k++) {
            try {
                threadClients[k].join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        log.info(
                "Total Ops submitted {}, actual TPS {}",
                totalOpsAllThread.get(),
                totalOpsAllThread.get() / ((float) (testDuration.getAsLong() * 60)));
        return false;
    }

    void testRun(HapiSpec spec) {
        double _targetTps = targetTps.getAsDouble();
        long _testDuration = testDuration.getAsLong();
        TimeUnit _ofUnit = ofUnit.get();
        int totalOps = 0;
        float currentTPS = 0;
        Stopwatch duration = createStarted();

        boolean reported = false;
        Stopwatch statDuration = duration;
        int submitOps = 0; // submitted tran during the stat window
        while (duration.elapsed(_ofUnit) < _testDuration) {
            HapiSpecOperation[] ops = opSource.get();
            allRunFor(spec, ops);
            submitOps += ops.length;
            totalOps += ops.length;

            long elapsedMS = statDuration.elapsed(MILLISECONDS);
            currentTPS = submitOps / (elapsedMS * 0.001f);

            if (statDuration.elapsed(SECONDS) % 10 == 0) { // report periodically
                if (!reported) {
                    log.info(
                            "Thread {} ops {} current TPS {}",
                            Thread.currentThread().getName(),
                            submitOps,
                            currentTPS);
                    reported = true;
                    submitOps = 0;
                    statDuration = createStarted();
                }
            } else {
                reported = false;
            }
            try {
                if (currentTPS > _targetTps) {
                    long pauseMillieSeconds = (long) ((submitOps / (float) _targetTps) * 1000 - elapsedMS);
                    Thread.sleep(Math.max(5, pauseMillieSeconds));
                }
            } catch (InterruptedException irrelevant) {
            }
        }
        log.info(
                "Thread {} final ops {} in {} seconds, TPS {} ",
                Thread.currentThread().getName(),
                totalOps,
                duration.elapsed(SECONDS),
                currentTPS);

        totalOpsAllThread.addAndGet(totalOps);
    }
}

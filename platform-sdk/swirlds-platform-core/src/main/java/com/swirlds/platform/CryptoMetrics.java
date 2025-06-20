// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_11_3;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.Metrics;

/**
 * This class collects and reports various statistics about advanced cryptography module operation.
 */
public final class CryptoMetrics {

    private static final String CATEGORY = "crypto";

    private static final RunningAverageMetric.Config AVG_DIGEST_QUEUE_DEPTH_CONFIG = new RunningAverageMetric.Config(
                    CATEGORY, "DigQuDepth")
            .withDescription("average digest queue depth")
            .withFormat(FORMAT_11_3);
    private static final RunningAverageMetric.Config AVG_SIG_QUEUE_DEPTH_CONFIG = new RunningAverageMetric.Config(
                    CATEGORY, "SigQuDepth")
            .withDescription("average signature queue depth")
            .withFormat(FORMAT_11_3);
    private static final RunningAverageMetric.Config AVG_DIGEST_BATCH_SIZE_CONFIG = new RunningAverageMetric.Config(
                    CATEGORY, "DigBatchSz")
            .withDescription("average digest batch size")
            .withFormat(FORMAT_11_3);
    private static final LongAccumulator.Config MIN_DIGEST_BATCH_SIZE_CONFIG = new LongAccumulator.Config(
                    CATEGORY, "MinDigBatchSz")
            .withAccumulator(Math::min)
            .withDescription("minimum digest batch size")
            .withInitialValue(Long.MAX_VALUE);
    private static final LongAccumulator.Config MAX_DIGEST_BATCH_SIZE_CONFIG = new LongAccumulator.Config(
                    CATEGORY, "MaxDigBatchSz")
            .withDescription("maximum digest batch size")
            .withInitialValue(Long.MIN_VALUE);
    private static final RunningAverageMetric.Config AVG_SIG_BATCH_SIZE_CONFIG = new RunningAverageMetric.Config(
                    CATEGORY, "SigBatchSz")
            .withDescription("average signature batch size")
            .withFormat(FORMAT_11_3);
    private static final LongAccumulator.Config MIN_SIG_BATCH_SIZE_CONFIG = new LongAccumulator.Config(
                    CATEGORY, "MinSigBatchSz")
            .withAccumulator(Math::min)
            .withDescription("minimum signature batch size")
            .withFormat("%,d")
            .withInitialValue(Long.MAX_VALUE);
    private static final LongAccumulator.Config MAX_SIG_BATCH_SIZE_CONFIG = new LongAccumulator.Config(
                    CATEGORY, "MaxSigBatchSz")
            .withDescription("maximum signature batch size")
            .withFormat("%,d")
            .withInitialValue(Long.MIN_VALUE);
    private static final SpeedometerMetric.Config DIG_WORK_PULSE_PER_SECOND_CONFIG = new SpeedometerMetric.Config(
                    CATEGORY, "DigPulse_per_sec")
            .withDescription("average digest worker pulses per second")
            .withFormat(FORMAT_11_3);
    private static final SpeedometerMetric.Config DIG_LOCK_UPGRADES_PER_SECOND_CONFIG = new SpeedometerMetric.Config(
                    CATEGORY, "DigLockUp_per_sec")
            .withDescription("average digest lock upgrades per second")
            .withFormat(FORMAT_11_3);
    private static final SpeedometerMetric.Config DIG_SPANS_PER_SECOND_CONFIG = new SpeedometerMetric.Config(
                    CATEGORY, "DigSpans_per_sec")
            .withDescription("average: digest batch spans per second")
            .withFormat(FORMAT_11_3);
    private static final SpeedometerMetric.Config DIG_BATCHES_PER_SECOND_CONFIG = new SpeedometerMetric.Config(
                    CATEGORY, "DigBatches_per_sec")
            .withDescription("average: digest batches created per second")
            .withFormat(FORMAT_11_3);
    private static final SpeedometerMetric.Config DIG_PER_SEC_CONFIG = new SpeedometerMetric.Config(
                    CATEGORY, "Dig_per_sec")
            .withDescription("number of digests per second (complete)")
            .withFormat(FORMAT_11_3);
    private static final SpeedometerMetric.Config SIG_WORK_PULSE_PER_SECOND_CONFIG = new SpeedometerMetric.Config(
                    CATEGORY, "SigPulse_per_sec")
            .withDescription("average Signature worker pulses per second")
            .withFormat(FORMAT_11_3);
    private static final RunningAverageMetric.Config AVG_DIGEST_TIME_CONFIG = new RunningAverageMetric.Config(
                    CATEGORY, "DigWrkTime")
            .withDescription("average: time spent (in millis) in digest worker pulses")
            .withFormat(FORMAT_11_3);
    private static final RunningAverageMetric.Config AVG_SIG_TIME_CONFIG = new RunningAverageMetric.Config(
                    CATEGORY, "SigWrkTime")
            .withDescription("average: time spent (in millis) in signature worker pulses")
            .withFormat(FORMAT_11_3);
    private static final RunningAverageMetric.Config AVG_DIGEST_WORK_ITEM_SUBMIT_TIME_CONFIG =
            new RunningAverageMetric.Config(CATEGORY, "DigSubWrkItmTime")
                    .withDescription("average: time spent (in millis) in digest submission")
                    .withFormat(FORMAT_11_3);
    private static final RunningAverageMetric.Config AVG_SIG_WORK_ITEM_SUBMIT_TIME_CONFIG =
            new RunningAverageMetric.Config(CATEGORY, "SigSubWrkItmTime")
                    .withDescription("average: time spent (in millis) in signature verification submission")
                    .withFormat(FORMAT_11_3);
    private static final SpeedometerMetric.Config SIG_LOCK_UPGRADES_PER_SECOND_CONFIG = new SpeedometerMetric.Config(
                    CATEGORY, "SigLockUp_per_sec")
            .withDescription("average Signature lock upgrades per second")
            .withFormat(FORMAT_11_3);
    private static final SpeedometerMetric.Config SIG_SPANS_PER_SECOND_CONFIG = new SpeedometerMetric.Config(
                    CATEGORY, "SigSpans_per_sec")
            .withDescription("average: signature verification batch spans per second")
            .withFormat(FORMAT_11_3);
    private static final SpeedometerMetric.Config SIG_BATCHES_PER_SECOND_CONFIG = new SpeedometerMetric.Config(
                    CATEGORY, "SigBatches_per_sec")
            .withDescription("average: signature verification batches created per second")
            .withFormat(FORMAT_11_3);
    private static final RunningAverageMetric.Config AVG_DIGEST_SLICE_SIZE_CONFIG = new RunningAverageMetric.Config(
                    CATEGORY, "DigSliceSz")
            .withDescription("average digest slice size")
            .withFormat(FORMAT_11_3);
    private static final RunningAverageMetric.Config AVG_SIG_SLICE_SIZE_CONFIG = new RunningAverageMetric.Config(
                    CATEGORY, "SigSliceSz")
            .withDescription("average signature slice size")
            .withFormat(FORMAT_11_3);
    private static final SpeedometerMetric.Config SIG_PER_SEC_CONFIG = new SpeedometerMetric.Config(
                    CATEGORY, "Sig_per_sec")
            .withDescription("number of signature verifications per second (complete)")
            .withFormat(FORMAT_11_3);
    private static final SpeedometerMetric.Config SIG_VALID_PER_SEC_CONFIG = new SpeedometerMetric.Config(
                    CATEGORY, "SigVal_per_sec")
            .withDescription("number of valid signatures per second")
            .withFormat(FORMAT_11_3);
    private static final SpeedometerMetric.Config SIG_INVALID_PER_SEC_CONFIG = new SpeedometerMetric.Config(
                    CATEGORY, "SigInval_per_sec")
            .withDescription("number of invalid signatures per second")
            .withFormat(FORMAT_11_3);
    private static final RunningAverageMetric.Config AVG_SIG_INTAKE_QUEUE_DEPTH_CONFIG =
            new RunningAverageMetric.Config(CATEGORY, "SigIntakeQueueDepth")
                    .withDescription("depth of the signature intake queue")
                    .withFormat(FORMAT_11_3);
    private static final SpeedometerMetric.Config SIG_INTAKE_PULSE_PER_SECOND_CONFIG = new SpeedometerMetric.Config(
                    CATEGORY, "SigIntakePulse_per_sec")
            .withDescription("number of times the signature intake worker thread is executed per second")
            .withFormat(FORMAT_11_3);
    private static final RunningAverageMetric.Config AVG_SIG_INTAKE_PULSE_TIME_CONFIG = new RunningAverageMetric.Config(
                    CATEGORY, "SigIntakePulseTime")
            .withDescription("average time spent (in millis) of each signature intake execution")
            .withFormat(FORMAT_11_3);
    private static final RunningAverageMetric.Config AVG_SIG_INTAKE_ENQUEUE_TIME_CONFIG =
            new RunningAverageMetric.Config(CATEGORY, "SigIntakeEnqueueTime")
                    .withDescription("average time spent (in millis) of each intake enqueue call")
                    .withFormat(FORMAT_11_3);
    private static final RunningAverageMetric.Config AVG_SIG_INTAKE_LIST_SIZE_CONFIG = new RunningAverageMetric.Config(
                    CATEGORY, "SigIntakeListSize")
            .withDescription("average size of each list sent to the intake worker")
            .withFormat(FORMAT_11_3);
    private static final RunningAverageMetric.Config AVG_PLATFORM_ENQUEUE_TIME_CONFIG = new RunningAverageMetric.Config(
                    CATEGORY, "PlatSigEnqueueTime")
            .withDescription("average time spent (in millis) by the platform enqueuing signatures")
            .withFormat(FORMAT_11_3);
    private static final RunningAverageMetric.Config AVG_PLATFORM_EXPAND_TIME_CONFIG = new RunningAverageMetric.Config(
                    CATEGORY, "PlatSigExpandTime")
            .withDescription("average time spent (in millis) by the platform calling the expandSignatures " + "method")
            .withFormat(FORMAT_11_3);
    private static final Counter.Config TOTAL_DIGESTS_CONFIG =
            new Counter.Config(CATEGORY, "TtlDig").withDescription("running total: digests computed");
    private static final Counter.Config TOTAL_SIG_CONFIG =
            new Counter.Config(CATEGORY, "TtlSig").withDescription("running total: Signatures Verified");
    private static final Counter.Config TOTAL_SIG_VALID_CONFIG =
            new Counter.Config(CATEGORY, "TtlSigVal").withDescription("running total: valid signatures verified");
    private static final Counter.Config TOTAL_SIG_INVALID_CONFIG =
            new Counter.Config(CATEGORY, "TtlSigInval").withDescription("running total: invalid signatures verified");

    private final RunningAverageMetric avgDigestQueueDepth;
    private final RunningAverageMetric avgDigestBatchSize;
    private final SpeedometerMetric digWorkPulsePerSecond;
    private final RunningAverageMetric avgDigestTime;
    private final RunningAverageMetric avgDigestWorkItemSubmitTime;
    private final SpeedometerMetric digLockUpgradesPerSecond;
    private final SpeedometerMetric digSpansPerSecond;
    private final SpeedometerMetric digBatchesPerSecond;
    private final RunningAverageMetric avgDigestSliceSize;
    private final SpeedometerMetric digPerSec;
    private final Counter totalDigests;
    private final LongAccumulator minDigestBatchSize;
    private final LongAccumulator maxDigestBatchSize;
    private final RunningAverageMetric avgSigQueueDepth;
    private final RunningAverageMetric avgSigBatchSize;
    private final SpeedometerMetric sigWorkPulsePerSecond;
    private final RunningAverageMetric avgSigTime;
    private final RunningAverageMetric avgSigWorkItemSubmitTime;
    private final SpeedometerMetric sigLockUpgradesPerSecond;
    private final SpeedometerMetric sigSpansPerSecond;
    private final SpeedometerMetric sigBatchesPerSecond;
    private final RunningAverageMetric avgSigSliceSize;
    private final SpeedometerMetric sigPerSec;
    private final SpeedometerMetric sigValidPerSec;
    private final SpeedometerMetric sigInvalidPerSec;
    private final RunningAverageMetric avgSigIntakeQueueDepth;
    private final SpeedometerMetric sigIntakePulsePerSecond;
    private final RunningAverageMetric avgSigIntakePulseTime;
    private final RunningAverageMetric avgSigIntakeEnqueueTime;
    private final RunningAverageMetric avgSigIntakeListSize;
    private final RunningAverageMetric avgPlatformEnqueueTime;
    private final RunningAverageMetric avgPlatformExpandTime;
    private final Counter totalSig;
    private final Counter totalSigValid;
    private final Counter totalSigInvalid;
    private final LongAccumulator minSigBatchSize;
    private final LongAccumulator maxSigBatchSize;

    // private instance, so that it can be
    // accessed by only by getInstance() method
    private static volatile CryptoMetrics instance;
    private static boolean isRecording = false;
    private static volatile Metrics metrics;

    public static CryptoMetrics getInstance() {
        // Double-Checked Locking works if the field is volatile from JDK5 onwards
        if (instance == null) {
            // synchronized block to remove overhead
            synchronized (CryptoMetrics.class) {
                if (instance == null) {
                    // if instance is null, initialize
                    instance = new CryptoMetrics();
                }
            }
        }
        return instance;
    }

    public static void registerMetrics(final Metrics metrics) {
        CryptoMetrics.metrics = metrics;
        getInstance(); // enforce initialization
    }

    private CryptoMetrics() {
        if (metrics == null) {
            throw new IllegalStateException("Metrics has not been set");
        }
        avgDigestQueueDepth = metrics.getOrCreate(AVG_DIGEST_QUEUE_DEPTH_CONFIG);
        avgSigQueueDepth = metrics.getOrCreate(AVG_SIG_QUEUE_DEPTH_CONFIG);
        avgDigestBatchSize = metrics.getOrCreate(AVG_DIGEST_BATCH_SIZE_CONFIG);
        minDigestBatchSize = metrics.getOrCreate(MIN_DIGEST_BATCH_SIZE_CONFIG);
        maxDigestBatchSize = metrics.getOrCreate(MAX_DIGEST_BATCH_SIZE_CONFIG);
        avgSigBatchSize = metrics.getOrCreate(AVG_SIG_BATCH_SIZE_CONFIG);
        minSigBatchSize = metrics.getOrCreate(MIN_SIG_BATCH_SIZE_CONFIG);
        maxSigBatchSize = metrics.getOrCreate(MAX_SIG_BATCH_SIZE_CONFIG);
        digWorkPulsePerSecond = metrics.getOrCreate(DIG_WORK_PULSE_PER_SECOND_CONFIG);
        digLockUpgradesPerSecond = metrics.getOrCreate(DIG_LOCK_UPGRADES_PER_SECOND_CONFIG);
        digSpansPerSecond = metrics.getOrCreate(DIG_SPANS_PER_SECOND_CONFIG);
        digBatchesPerSecond = metrics.getOrCreate(DIG_BATCHES_PER_SECOND_CONFIG);
        digPerSec = metrics.getOrCreate(DIG_PER_SEC_CONFIG);
        sigWorkPulsePerSecond = metrics.getOrCreate(SIG_WORK_PULSE_PER_SECOND_CONFIG);
        avgDigestTime = metrics.getOrCreate(AVG_DIGEST_TIME_CONFIG);
        avgSigTime = metrics.getOrCreate(AVG_SIG_TIME_CONFIG);
        avgDigestWorkItemSubmitTime = metrics.getOrCreate(AVG_DIGEST_WORK_ITEM_SUBMIT_TIME_CONFIG);
        avgSigWorkItemSubmitTime = metrics.getOrCreate(AVG_SIG_WORK_ITEM_SUBMIT_TIME_CONFIG);
        sigLockUpgradesPerSecond = metrics.getOrCreate(SIG_LOCK_UPGRADES_PER_SECOND_CONFIG);
        sigSpansPerSecond = metrics.getOrCreate(SIG_SPANS_PER_SECOND_CONFIG);
        sigBatchesPerSecond = metrics.getOrCreate(SIG_BATCHES_PER_SECOND_CONFIG);
        avgDigestSliceSize = metrics.getOrCreate(AVG_DIGEST_SLICE_SIZE_CONFIG);
        avgSigSliceSize = metrics.getOrCreate(AVG_SIG_SLICE_SIZE_CONFIG);
        sigPerSec = metrics.getOrCreate(SIG_PER_SEC_CONFIG);
        sigValidPerSec = metrics.getOrCreate(SIG_VALID_PER_SEC_CONFIG);
        sigInvalidPerSec = metrics.getOrCreate(SIG_INVALID_PER_SEC_CONFIG);
        avgSigIntakeQueueDepth = metrics.getOrCreate(AVG_SIG_INTAKE_QUEUE_DEPTH_CONFIG);
        sigIntakePulsePerSecond = metrics.getOrCreate(SIG_INTAKE_PULSE_PER_SECOND_CONFIG);
        avgSigIntakePulseTime = metrics.getOrCreate(AVG_SIG_INTAKE_PULSE_TIME_CONFIG);
        avgSigIntakeEnqueueTime = metrics.getOrCreate(AVG_SIG_INTAKE_ENQUEUE_TIME_CONFIG);
        avgSigIntakeListSize = metrics.getOrCreate(AVG_SIG_INTAKE_LIST_SIZE_CONFIG);
        avgPlatformEnqueueTime = metrics.getOrCreate(AVG_PLATFORM_ENQUEUE_TIME_CONFIG);
        avgPlatformExpandTime = metrics.getOrCreate(AVG_PLATFORM_EXPAND_TIME_CONFIG);
        totalDigests = metrics.getOrCreate(TOTAL_DIGESTS_CONFIG);
        totalSig = metrics.getOrCreate(TOTAL_SIG_CONFIG);
        totalSigValid = metrics.getOrCreate(TOTAL_SIG_VALID_CONFIG);
        totalSigInvalid = metrics.getOrCreate(TOTAL_SIG_INVALID_CONFIG);
    }

    static void startRecording() {
        if (!isRecording) {
            isRecording = true;
        }
    }

    public static boolean recordingStatus() {
        return isRecording;
    }

    public void setSigIntakeWorkerValues(final int queueDepth, final double workerTime, final int listSize) {
        sigIntakePulsePerSecond.cycle();
        avgSigIntakeQueueDepth.update(queueDepth);
        avgSigIntakePulseTime.update(workerTime);
        avgSigIntakeListSize.update(listSize);
    }

    public void setSigIntakeEnqueueValues(final double enqueueTime) {
        avgSigIntakeEnqueueTime.update(enqueueTime);
    }

    public void setPlatformSigIntakeValues(final double enqueueTime, final double expandTime) {
        avgPlatformEnqueueTime.update(enqueueTime);
        avgPlatformExpandTime.update(expandTime);
    }

    private void setDigestWorkerValues(long digestQueueDepth, long digestBatchSize, double time) {
        avgDigestQueueDepth.update(digestQueueDepth);
        avgDigestBatchSize.update(digestBatchSize);
        digWorkPulsePerSecond.cycle();
        avgDigestTime.update(time);
        minDigestBatchSize.update(digestBatchSize);
        maxDigestBatchSize.update(digestBatchSize);
    }

    private void setSigWorkerValues(long sigQueueDepth, long sigBatchSize, double time) {
        avgSigQueueDepth.update(sigQueueDepth);
        avgSigBatchSize.update(sigBatchSize);
        sigWorkPulsePerSecond.cycle();
        avgSigTime.update(time);
        minSigBatchSize.update(sigBatchSize);
        maxSigBatchSize.update(sigBatchSize);
    }

    private void setSigSubmitWorkItem(double time, boolean lockUpgraded) {
        avgSigWorkItemSubmitTime.update(time);
        if (lockUpgraded) {
            sigLockUpgradesPerSecond.cycle();
        }
    }

    private void setDigestSubmitWorkItem(double time, boolean lockUpgraded) {
        avgDigestWorkItemSubmitTime.update(time);
        if (lockUpgraded) {
            digLockUpgradesPerSecond.cycle();
        }
    }

    public void setDigestHandleExecution(double sliceSize) {
        avgDigestSliceSize.update(sliceSize);
        digPerSec.cycle();
        totalDigests.increment();
    }

    public void setSigHandleExecution(double sliceSize, boolean isValid) {
        avgSigSliceSize.update(sliceSize);
        sigPerSec.cycle();
        totalSig.increment();
        if (isValid) {
            sigValidPerSec.cycle();
            totalSigValid.increment();
        } else {
            sigInvalidPerSec.cycle();
            totalSigInvalid.increment();
        }
    }
}

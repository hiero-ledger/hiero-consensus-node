// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import java.util.concurrent.atomic.AtomicLong;

public class AtomicReconnectMapStats implements ReconnectMapStats {

    private final AtomicLong transfersFromTeacher = new AtomicLong();
    private final AtomicLong transfersFromLearner = new AtomicLong();
    private final AtomicLong internalHashes = new AtomicLong();
    private final AtomicLong internalCleanHashes = new AtomicLong();
    private final AtomicLong internalData = new AtomicLong();
    private final AtomicLong internalCleanData = new AtomicLong();
    private final AtomicLong leafHashes = new AtomicLong();
    private final AtomicLong leafCleanHashes = new AtomicLong();
    private final AtomicLong leafData = new AtomicLong();
    private final AtomicLong leafCleanData = new AtomicLong();

    @Override
    public void incrementTransfersFromTeacher() {
        transfersFromTeacher.incrementAndGet();
    }

    @Override
    public void incrementTransfersFromLearner() {
        transfersFromLearner.incrementAndGet();
    }

    @Override
    public void incrementInternalHashes(final int hashNum, final int cleanHashNum) {
        internalHashes.addAndGet(hashNum);
        internalCleanHashes.addAndGet(cleanHashNum);
    }

    @Override
    public void incrementInternalData(final int dataNum, final int cleanDataNum) {
        internalData.addAndGet(dataNum);
        internalCleanData.addAndGet(cleanDataNum);
    }

    @Override
    public void incrementLeafHashes(final int hashNum, final int cleanHashNum) {
        leafHashes.addAndGet(hashNum);
        leafCleanHashes.addAndGet(cleanHashNum);
    }

    @Override
    public void incrementLeafData(final int dataNum, final int cleanDataNum) {
        leafData.addAndGet(dataNum);
        leafCleanData.addAndGet(cleanDataNum);
    }

    public long transfersFromTeacher() {
        return transfersFromTeacher.get();
    }

    public long transfersFromLearner() {
        return transfersFromLearner.get();
    }

    @Override
    public String format() {
        return "AtomicReconnectMapStats: "
                + "transfersFromTeacher=" + transfersFromTeacher.get()
                + "; transfersFromLearner=" + transfersFromLearner.get()
                + "; internalHashes=" + internalHashes.get()
                + "; internalCleanHashes=" + internalCleanHashes.get()
                + "; internalData=" + internalData.get()
                + "; internalCleanData=" + internalCleanData.get()
                + "; leafHashes=" + leafHashes.get()
                + "; leafCleanHashes=" + leafCleanHashes.get()
                + "; leafData=" + leafData.get()
                + "; leafCleanData=" + leafCleanData.get();
    }
}

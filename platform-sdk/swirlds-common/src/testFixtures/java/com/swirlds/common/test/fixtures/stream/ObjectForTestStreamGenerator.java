// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.stream;

import java.time.Instant;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class ObjectForTestStreamGenerator {
    /**
     * number of objects to be generated
     */
    private int totalNum;
    /**
     * interval in ms between each two adjacent objects to be generated
     */
    private int intervalMs;
    /**
     * timeStamp of the next object
     */
    private Instant nextTimestamp;
    /**
     * idx of next object;
     */
    private int idx;
    /**
     * @param totalNum
     */
    public ObjectForTestStreamGenerator(int totalNum, int intervalMs, Instant firstTimestamp) {
        this.totalNum = totalNum;
        this.intervalMs = intervalMs;
        nextTimestamp = firstTimestamp;
    }

    public Iterator<ObjectForTestStream> getIterator() {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return idx < totalNum;
            }

            @Override
            public ObjectForTestStream next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                ObjectForTestStream object = new ObjectForTestStream(idx, nextTimestamp);
                idx++;
                nextTimestamp = nextTimestamp.plusMillis(intervalMs);
                return object;
            }
        };
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.stream;

import com.swirlds.common.stream.internal.LinkedObjectStream;
import java.util.concurrent.CountDownLatch;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.RunningHashable;

/**
 * This stream contains a CountDownLatch(1) object.
 * Once this stream receives expected number of objects from its upper stream, the CountDownLatch.countDown will be
 * called.
 *
 * @param <T>
 * 		RunningHashable
 */
public class CountDownLatchStream<T extends RunningHashable> implements LinkedObjectStream<T> {
    /** the countDownLatch will be countDown once this stream receives expected count of objects */
    private CountDownLatch countDownLatch;
    /** how many objects it expects to receive from its upper stream */
    private int expectedCount;

    public CountDownLatchStream(final CountDownLatch countDownLatch, final int expectedCount) {
        this.countDownLatch = countDownLatch;
        this.expectedCount = expectedCount;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setRunningHash(final Hash hash) {
        // intentionally blank
        // because it would be called by upperStream, but this stream don't use runningHash
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addObject(T t) {
        expectedCount--;
        if (expectedCount == 0) {
            countDownLatch.countDown();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        // intentionally blank
        // because it would be called by upperStream, but this stream don't need to clear anything
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        // intentionally blank
        // because it would be called by upperStream, but this stream don't have close logic
    }
}

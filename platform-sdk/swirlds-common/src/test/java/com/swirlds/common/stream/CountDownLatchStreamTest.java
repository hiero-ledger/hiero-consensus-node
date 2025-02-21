// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.stream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.swirlds.common.test.fixtures.stream.CountDownLatchStream;
import com.swirlds.common.test.fixtures.stream.ObjectForTestStream;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class CountDownLatchStreamTest {

    @Test
    void countDownLatchStreamTest() {
        CountDownLatch countDownLatch = mock(CountDownLatch.class);
        final int expectedCount = 10;
        CountDownLatchStream<ObjectForTestStream> stream = new CountDownLatchStream<>(countDownLatch, expectedCount);
        for (int i = 0; i < expectedCount; i++) {
            // when the stream haven't received enough objects, countDown() is not called
            verify(countDownLatch, never()).countDown();
            stream.addObject(ObjectForTestStream.getRandomObjectForTestStream(4));
        }
        // only when the stream have received enough objects, countDown() is called
        verify(countDownLatch).countDown();
    }
}

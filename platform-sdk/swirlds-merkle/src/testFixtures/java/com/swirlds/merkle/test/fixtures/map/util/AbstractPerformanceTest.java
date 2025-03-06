// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.util;

import java.util.Arrays;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public abstract class AbstractPerformanceTest {

    private static final Logger logger = LogManager.getLogger(AbstractPerformanceTest.class);

    protected void measure(
            final Consumer<Void> methodToMeasure,
            final Consumer<Void> prepareMethod,
            final int numberOfTimesToExecute,
            final String measureName) {
        if (numberOfTimesToExecute < 3) {
            throw new IllegalArgumentException(
                    "Number of times to execute must be at least 3: " + numberOfTimesToExecute);
        }

        long start;
        long end;
        final long[] runningTimes = new long[numberOfTimesToExecute];
        for (int index = 0; index < numberOfTimesToExecute; index++) {
            prepareMethod.accept(null);
            start = System.currentTimeMillis();
            methodToMeasure.accept(null);
            end = System.currentTimeMillis();
            runningTimes[index] = end - start;
        }

        Arrays.sort(runningTimes);
        long sum = 0;
        final long limit = numberOfTimesToExecute - 1;
        for (int index = 1; index < limit; index++) {
            sum += runningTimes[index];
        }

        final long average = sum / (numberOfTimesToExecute - 2);
        logger.info(
                "The test {} took on average {}, with min {} and max {}",
                measureName,
                average,
                runningTimes[1],
                runningTimes[runningTimes.length - 2]);
    }
}

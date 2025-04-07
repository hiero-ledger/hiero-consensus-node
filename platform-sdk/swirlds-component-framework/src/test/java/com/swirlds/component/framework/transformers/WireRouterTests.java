// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.transformers;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.component.framework.wires.output.OutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WireRouterTests {

    private enum TestDataType {
        FOO, // Long values
        BAR, // Long values
        BAZ; // Boolean values

        /**
         * Create a new {@link RoutableData} object with the given data.
         *
         * @param data the data
         * @return the new {@link RoutableData} object
         */
        @NonNull
        public RoutableData<TestDataType> of(@NonNull final Object data) {
            return new RoutableData<>(this, data);
        }
    }

    @Test
    void basicBehaviorTest() {
        final Random random = getRandomPrintSeed();

        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final WireRouter<TestDataType> router = new WireRouter<>(model, "router", "router input", TestDataType.class);

        final AtomicLong latestFoo = new AtomicLong();
        final AtomicLong latestBar = new AtomicLong();
        final AtomicBoolean latestBaz = new AtomicBoolean();

        final OutputWire<Long> fooOutput = router.getOutput(TestDataType.FOO);
        final OutputWire<Long> barOutput = router.getOutput(TestDataType.BAR);
        final OutputWire<Boolean> bazOutput = router.getOutput(TestDataType.BAZ);

        fooOutput.solderTo("fooHandler", "fooInput", latestFoo::set);
        barOutput.solderTo("barHandler", "barInput", latestBar::set);
        bazOutput.solderTo("bazHandler", "bazInput", latestBaz::set);

        long expectedFoo = 0;
        long expectedBar = 0;
        boolean expectedBaz = false;

        for (int i = 0; i < 1000; i++) {
            final double choice = random.nextDouble();
            if (choice < 1.0 / 3.0) {
                expectedFoo = random.nextLong();
                router.getInput().put(TestDataType.FOO.of(expectedFoo));
            } else if (choice < 2.0 / 3.0) {
                expectedBar = random.nextLong();
                router.getInput().put(TestDataType.BAR.of(expectedBar));
            } else {
                expectedBaz = random.nextBoolean();
                router.getInput().put(TestDataType.BAZ.of(expectedBaz));
            }
            assertEquals(expectedFoo, latestFoo.get());
            assertEquals(expectedBar, latestBar.get());
            assertEquals(expectedBaz, latestBaz.get());
        }
    }
}

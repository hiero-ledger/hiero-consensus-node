// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RuntimeObjectRegistryTest {

    @AfterEach
    void cleanUp() {
        RuntimeObjectRegistry.reset();
    }

    @Test
    void testInitializingWithNullFails() {
        //noinspection DataFlowIssue
        assertThatCode(() -> RuntimeObjectRegistry.initialize(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testUninitializedRegistryUsesRealTime() {
        final RuntimeObjectRecord objectRecord = RuntimeObjectRegistry.createRecord(Object.class);
        assertThat(objectRecord.getAge(Instant.now())).isLessThan(Duration.ofMinutes(5));
    }

    @Test
    void testInitializedRegistryUsesFakeTime() {
        final Time fakeTime = new FakeTime();
        RuntimeObjectRegistry.initialize(fakeTime);

        final RuntimeObjectRecord objectRecord = RuntimeObjectRegistry.createRecord(Object.class);
        assertThat(objectRecord.getAge(fakeTime.now().plusSeconds(5L))).isEqualTo(Duration.ofSeconds(5L));
    }

    @Test
    void testAlreadyUsedRegistryCannotBeInitialized() {
        final Time fakeTime = new FakeTime();

        RuntimeObjectRegistry.createRecord(Object.class);
        assertThatCode(() -> RuntimeObjectRegistry.initialize(fakeTime)).isInstanceOf(IllegalStateException.class);
    }
}

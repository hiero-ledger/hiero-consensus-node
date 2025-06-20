// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.service.contract.impl.infra.RentCalculator;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RentCalculatorTest {
    private static final Instant NOW = Instant.ofEpochSecond(1_234_567L, 890);

    private RentCalculator subject;

    @BeforeEach
    void setUp() {
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        subject = new RentCalculator(NOW, config);
    }

    @Test
    void calculatesZeroRentForNow() {
        assertEquals(0, subject.computeFor(1, 2, 3, 4));
    }
}

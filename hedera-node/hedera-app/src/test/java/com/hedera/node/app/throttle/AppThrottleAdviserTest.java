// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AppThrottleAdviserTest {
    @Mock
    private NetworkUtilizationManager networkUtilizationManager;

    private static final Instant CONSENSUS_NOW = Instant.parse("2007-12-03T10:15:30.00Z");

    private AppThrottleAdviser subject;

    @BeforeEach
    void setup() {
        subject = new AppThrottleAdviser(networkUtilizationManager, CONSENSUS_NOW);
    }

    @Test
    void forwardsShouldThrottleNOfUnscaled() {
        subject.shouldThrottleNOfUnscaled(2, CRYPTO_TRANSFER);
        verify(networkUtilizationManager).shouldThrottleNOfUnscaled(2, CRYPTO_TRANSFER, CONSENSUS_NOW);
    }
}

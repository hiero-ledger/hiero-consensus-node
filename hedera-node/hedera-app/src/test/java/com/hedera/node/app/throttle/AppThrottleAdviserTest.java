// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.node.app.spi.throttle.ThrottleAdviser.ConsensusThrottleRejection.BEYOND_CAPACITY;
import static com.hedera.node.app.spi.throttle.ThrottleAdviser.ConsensusThrottleRejection.NONE;
import static com.hedera.node.app.spi.throttle.ThrottleAdviser.ConsensusThrottleRejection.TEMPORARILY_BUSY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

    @Test
    void forwardsHighVolumeThrottleUtilization() {
        given(networkUtilizationManager.highVolumeThrottleUtilization(CRYPTO_TRANSFER, CONSENSUS_NOW))
                .willReturn(2_500);

        assertEquals(2_500, subject.highVolumeThrottleUtilization(CRYPTO_TRANSFER));
        verify(networkUtilizationManager).highVolumeThrottleUtilization(CRYPTO_TRANSFER, CONSENSUS_NOW);
    }

    @Test
    @DisplayName("given an open capture with no rejection recorded, then its rejection is NONE")
    void givenOpenCaptureWithNoRejection_thenRejectionIsNone() {
        try (final var capture = subject.captureConsensusThrottleRejections()) {
            assertThat(capture.rejection()).isEqualTo(NONE);
        }
    }

    @Test
    @DisplayName("given only rejections beyond capacity, then the captured rejection is BEYOND_CAPACITY")
    void givenOnlyRejectionsBeyondCapacity_thenCapturedRejectionIsBeyondCapacity() {
        try (final var capture = subject.captureConsensusThrottleRejections()) {
            subject.recordConsensusThrottleRejection(true);
            subject.recordConsensusThrottleRejection(true);

            assertThat(capture.rejection()).isEqualTo(BEYOND_CAPACITY);
        }
    }

    @Test
    @DisplayName("given a temporarily busy rejection after one beyond capacity, then the captured rejection is "
            + "TEMPORARILY_BUSY")
    void givenTemporarilyBusyRejectionAfterBeyondCapacity_thenCapturedRejectionIsTemporarilyBusy() {
        try (final var capture = subject.captureConsensusThrottleRejections()) {
            subject.recordConsensusThrottleRejection(true);
            subject.recordConsensusThrottleRejection(false);

            assertThat(capture.rejection()).isEqualTo(TEMPORARILY_BUSY);
        }
    }

    @Test
    @DisplayName("given a temporarily busy rejection, when one beyond capacity follows, then the captured rejection "
            + "stays TEMPORARILY_BUSY")
    void givenTemporarilyBusyRejection_whenBeyondCapacityFollows_thenCapturedRejectionStaysTemporarilyBusy() {
        try (final var capture = subject.captureConsensusThrottleRejections()) {
            subject.recordConsensusThrottleRejection(false);

            subject.recordConsensusThrottleRejection(true);

            assertThat(capture.rejection()).isEqualTo(TEMPORARILY_BUSY);
        }
    }

    @Test
    @DisplayName("given a rejection recorded before the capture opened, then the capture does not see it")
    void givenRejectionBeforeCaptureOpened_thenCaptureDoesNotSeeIt() {
        subject.recordConsensusThrottleRejection(false);

        try (final var capture = subject.captureConsensusThrottleRejections()) {
            assertThat(capture.rejection()).isEqualTo(NONE);
        }
    }

    @Test
    @DisplayName("given a closed capture, when a rejection is recorded, then the capture does not see it")
    void givenClosedCapture_whenRejectionRecorded_thenCaptureDoesNotSeeIt() {
        final var capture = subject.captureConsensusThrottleRejections();
        capture.close();

        subject.recordConsensusThrottleRejection(false);

        assertThat(capture.rejection()).isEqualTo(NONE);
    }

    @Test
    @DisplayName("given a closed capture, when a new one opens, then it starts with no rejection")
    void givenClosedCapture_whenNewOneOpens_thenItStartsWithNoRejection() {
        try (final var _ = subject.captureConsensusThrottleRejections()) {
            subject.recordConsensusThrottleRejection(false);
        }

        try (final var second = subject.captureConsensusThrottleRejections()) {
            assertThat(second.rejection()).isEqualTo(NONE);
        }
    }

    @Test
    @DisplayName("given an open capture, when another one is opened, then it throws")
    void givenOpenCapture_whenAnotherOneIsOpened_thenThrows() {
        try (final var _ = subject.captureConsensusThrottleRejections()) {
            assertThatThrownBy(subject::captureConsensusThrottleRejections).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("given the N-of check would be throttled with empty buckets, then BEYOND_CAPACITY is captured")
    void givenNOfCheckThrottledWithEmptyBuckets_thenBeyondCapacityIsCaptured() {
        given(networkUtilizationManager.shouldThrottleNOfUnscaled(2, CRYPTO_TRANSFER, CONSENSUS_NOW))
                .willReturn(true);
        given(networkUtilizationManager.wouldThrottleNOfUnscaledWithEmptyBuckets(2, CRYPTO_TRANSFER, CONSENSUS_NOW))
                .willReturn(true);

        try (final var capture = subject.captureConsensusThrottleRejections()) {
            assertThat(subject.shouldThrottleNOfUnscaled(2, CRYPTO_TRANSFER)).isTrue();

            assertThat(capture.rejection()).isEqualTo(BEYOND_CAPACITY);
        }
    }

    @Test
    @DisplayName("given the N-of check passes, then no rejection is captured")
    void givenNOfCheckPasses_thenNoRejectionIsCaptured() {
        try (final var capture = subject.captureConsensusThrottleRejections()) {
            assertThat(subject.shouldThrottleNOfUnscaled(2, CRYPTO_TRANSFER)).isFalse();

            assertThat(capture.rejection()).isEqualTo(NONE);
        }
    }
}

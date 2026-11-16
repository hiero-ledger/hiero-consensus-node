// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.node.app.service.clpr.impl.ClprThrottlesUtils.effectiveMaxPayloadBytes;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprThrottles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClprThrottlesUtilsTest {

    private static final ClprThrottles LOCAL_THROTTLES = ClprThrottles.newBuilder()
            .maxMessagesPerBundle(1_000)
            .maxSyncBytes(4_194_304L)
            .build();

    @ParameterizedTest
    @ValueSource(ints = {1_024, 1_048_576})
    @DisplayName("given peer throttles with a payload limit, then the peer's limit is used")
    void givenPeerPayloadLimit_thenPeerLimitIsUsed(final int peerMaxPayloadBytes) {
        final var peerThrottles = LOCAL_THROTTLES
                .copyBuilder()
                .maxMessagePayloadBytes(peerMaxPayloadBytes)
                .build();

        assertThat(effectiveMaxPayloadBytes(peerThrottles, LOCAL_THROTTLES)).isEqualTo(peerMaxPayloadBytes);
    }

    @Test
    @DisplayName("given no peer throttles, then the local payload limit is used")
    void givenNoPeerThrottles_thenLocalLimitIsUsed() {
        assertThat(effectiveMaxPayloadBytes(null, LOCAL_THROTTLES)).isEqualTo(LOCAL_THROTTLES.maxMessagePayloadBytes());
    }

    @Test
    @DisplayName("given peer throttles without a payload limit, then the local payload limit is used")
    void givenPeerThrottlesWithoutPayloadLimit_thenLocalLimitIsUsed() {
        assertThat(effectiveMaxPayloadBytes(ClprThrottles.DEFAULT, LOCAL_THROTTLES))
                .isEqualTo(LOCAL_THROTTLES.maxMessagePayloadBytes());
    }
}

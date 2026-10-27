// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.node.config.testfixtures.ClprConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClprOperatorDenyListTest {

    private static final Bytes CHANNEL_ID = Bytes.fromHex("ab".repeat(32));

    @Test
    @DisplayName("a channel id matches with or without 0x and in either case")
    void matchesChannelIdsLeniently() {
        assertThat(isChannelDenied(Set.of("0x" + "AB".repeat(32)))).isTrue();
        assertThat(isChannelDenied(Set.of(" " + "ab".repeat(32) + " "))).isTrue();
        assertThat(isChannelDenied(Set.of("cd".repeat(32)))).isFalse();
        assertThat(isChannelDenied(Set.of())).isFalse();
    }

    @Test
    @DisplayName("an endpoint matches by address alone or by address and port")
    void matchesEndpoints() {
        final var ipv4 = endpoint("203.0.113.1", 50211);
        final var ipv6 = endpoint("2001:db8::1", 50211);

        assertThat(isEndpointDenied(Set.of("203.0.113.1"), ipv4)).isTrue();
        assertThat(isEndpointDenied(Set.of("203.0.113.1:50211"), ipv4)).isTrue();
        assertThat(isEndpointDenied(Set.of("203.0.113.1:50212"), ipv4)).isFalse();
        assertThat(isEndpointDenied(Set.of("[2001:db8::1]:50211"), ipv6)).isTrue();
        assertThat(isEndpointDenied(Set.of("2001:db8::1"), ipv6)).isTrue();
        assertThat(isEndpointDenied(Set.of("203.0.113.2"), ipv4)).isFalse();
        assertThat(isEndpointDenied(Set.of("203.0.113.1"), ClprEndpoint.DEFAULT))
                .isFalse();
    }

    private static boolean isChannelDenied(final Set<String> denied) {
        return ClprOperatorDenyList.isChannelDenied(
                ClprConfigBuilder.newBuilder().deniedChannelIds(denied).build(), CHANNEL_ID);
    }

    private static boolean isEndpointDenied(final Set<String> denied, final ClprEndpoint endpoint) {
        return ClprOperatorDenyList.isEndpointDenied(
                ClprConfigBuilder.newBuilder().deniedPeerEndpoints(denied).build(), endpoint);
    }

    private static ClprEndpoint endpoint(final String address, final int port) {
        return ClprEndpoint.newBuilder()
                .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                        .ipAddress(address)
                        .port(port)
                        .build())
                .build();
    }
}

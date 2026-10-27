// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.node.app.service.clpr.impl.ClprPeerEndpointPolicy;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClprPeerEndpointPolicyTest {

    private static final int PORT = 50211;

    @ParameterizedTest(name = "{0} is allowed")
    @ValueSource(strings = {"8.8.8.8", "203.0.113.7", "1.1.1.1", "2001:4860:4860::8888", "2606:4700:4700::1111"})
    @DisplayName("public IP literals are allowed")
    void allowsPublicLiterals(final String address) {
        assertThat(ClprPeerEndpointPolicy.isAllowed(address, PORT, false)).isTrue();
    }

    @ParameterizedTest(name = "{0} is rejected")
    @ValueSource(
            strings = {
                "127.0.0.1", // loopback
                "10.1.2.3", // private
                "172.16.0.1", // private
                "192.168.1.1", // private
                "169.254.169.254", // link-local, cloud metadata
                "100.64.0.1", // shared address space (CGNAT)
                "0.0.0.0", // unspecified
                "0.1.2.3", // this network
                "224.0.0.1", // multicast
                "240.0.0.1", // reserved
                "255.255.255.255", // broadcast
                "::", // unspecified
                "::1", // loopback
                "fe80::1", // link-local
                "fd00:ec2::254", // unique local, cloud metadata
                "fc00::1", // unique local
                "ff02::1", // multicast
                "::7f00:1", // IPv4-compatible
                "64:ff9b::a00:1", // NAT64
            })
    @DisplayName("non-public addresses are rejected")
    void rejectsNonPublicAddresses(final String address) {
        assertThat(ClprPeerEndpointPolicy.isAllowed(address, PORT, false)).isFalse();
    }

    @ParameterizedTest(name = "{0} is rejected")
    @ValueSource(
            strings = {
                "localhost", // DNS name
                "example.com", // DNS name
                "127.1", // short IPv4 form
                "2130706433", // decimal IPv4 form
                "010.0.0.1", // leading zeros
                "0x7f.0.0.1", // hex
                "::ffff:8.8.8.8", // IPv4-mapped
                "2001:db8::1%1", // zone id
                " 8.8.8.8", // whitespace
                "",
            })
    @DisplayName("anything but a canonical IP literal is rejected, even when non-public addresses are allowed")
    void rejectsNonLiterals(final String address) {
        assertThat(ClprPeerEndpointPolicy.isAllowed(address, PORT, true)).isFalse();
    }

    @ParameterizedTest(name = "port {0} is rejected")
    @ValueSource(ints = {0, -1, 65_536})
    @DisplayName("ports outside 1-65535 are rejected")
    void rejectsOutOfRangePorts(final int port) {
        assertThat(ClprPeerEndpointPolicy.isAllowed("8.8.8.8", port, true)).isFalse();
    }

    @Test
    @DisplayName("non-public addresses are allowed when the network allows them")
    void allowsNonPublicAddressesWhenConfigured() {
        assertThat(ClprPeerEndpointPolicy.isAllowed("127.0.0.1", PORT, true)).isTrue();
        assertThat(ClprPeerEndpointPolicy.isAllowed("10.0.0.7", PORT, true)).isTrue();
    }

    @Test
    @DisplayName("a list is allowed only if every endpoint is, and an endpoint needs a service endpoint")
    void checksEveryEndpoint() {
        final var publicEndpoint = endpoint("8.8.8.8");
        final var loopbackEndpoint = endpoint("127.0.0.1");

        assertThat(ClprPeerEndpointPolicy.allAllowed(List.of(), false)).isTrue();
        assertThat(ClprPeerEndpointPolicy.allAllowed(List.of(publicEndpoint), false))
                .isTrue();
        assertThat(ClprPeerEndpointPolicy.allAllowed(List.of(publicEndpoint, loopbackEndpoint), false))
                .isFalse();
        assertThat(ClprPeerEndpointPolicy.isAllowed(ClprEndpoint.DEFAULT, true)).isFalse();
    }

    private static ClprEndpoint endpoint(final String address) {
        return ClprEndpoint.newBuilder()
                .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                        .ipAddress(address)
                        .port(PORT)
                        .build())
                .build();
    }
}

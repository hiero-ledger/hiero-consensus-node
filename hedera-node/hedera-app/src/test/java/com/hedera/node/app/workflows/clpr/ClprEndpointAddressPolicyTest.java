// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.workflows.clpr.ClprEndpointClientImpl.ClientException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ClprEndpointAddressPolicyTest {
    private static final int PORT = 50211;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "1.0.0.0",
                "8.8.8.8",
                "9.255.255.255",
                "11.0.0.0",
                "100.63.255.255",
                "100.128.0.0",
                "126.255.255.255",
                "128.0.0.0",
                "169.253.255.255",
                "169.255.0.0",
                "172.15.255.255",
                "172.32.0.0",
                "191.255.255.255",
                "192.0.1.0",
                "192.0.1.255",
                "192.0.3.0",
                "192.88.98.255",
                "192.88.100.0",
                "192.167.255.255",
                "192.169.0.0",
                "198.17.255.255",
                "198.20.0.0",
                "198.51.99.255",
                "198.51.101.0",
                "203.0.112.255",
                "203.0.114.0",
                "223.255.255.255",
                "2000::",
                "2000:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
                "2001:200::",
                "2001:db7:ffff:ffff:ffff:ffff:ffff:ffff",
                "2001:db9::",
                "2001:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
                "2003::",
                "2001:4860:4860::8888",
                "2606:4700:4700::1111",
                "3ffe:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
                "3fff:1000::",
                "3fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"
            })
    void acceptsPublicAddressesAndRangeBoundaries(final String host) {
        final var address = ClprEndpointAddressPolicy.addressFor(host, PORT, Set.of());

        assertThat(address.isUnresolved()).isFalse();
        assertThat(address.getAddress()).isNotNull();
        assertThat(address.getPort()).isEqualTo(PORT);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0.0.0.0",
                "0.255.255.255",
                "10.0.0.0",
                "10.255.255.255",
                "100.64.0.0",
                "100.127.255.255",
                "127.0.0.0",
                "127.255.255.255",
                "169.254.0.0",
                "169.254.255.255",
                "172.16.0.0",
                "172.31.255.255",
                "192.0.0.0",
                "192.0.0.255",
                "192.0.2.0",
                "192.0.2.255",
                "192.88.99.0",
                "192.88.99.255",
                "192.168.0.0",
                "192.168.255.255",
                "198.18.0.0",
                "198.19.255.255",
                "198.51.100.0",
                "198.51.100.255",
                "203.0.113.0",
                "203.0.113.255",
                "224.0.0.0",
                "239.255.255.255",
                "240.0.0.0",
                "255.255.255.255",
                "::",
                "::1",
                "::192.168.1.1",
                "64:ff9b::808:808",
                "100::1",
                "1fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
                "2001::",
                "2001:1ff:ffff:ffff:ffff:ffff:ffff:ffff",
                "2001:db8::",
                "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff",
                "2002::",
                "2002:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
                "3fff::",
                "3fff:fff:ffff:ffff:ffff:ffff:ffff:ffff",
                "4000::",
                "fc00::1",
                "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
                "fe80::1",
                "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
                "ff00::",
                "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"
            })
    void rejectsSpecialAddressesByDefault(final String host) {
        assertThatThrownBy(() -> ClprEndpointAddressPolicy.addressFor(host, PORT, Set.of()))
                .isInstanceOf(ClientException.class);
    }

    @ParameterizedTest
    @CsvSource({"::ffff:8.8.8.8,8.8.8.8", "::ffff:0808:0808,8.8.8.8"})
    void normalizesIpv4MappedAddresses(final String host, final String expected) {
        final var address = ClprEndpointAddressPolicy.addressFor(host, PORT, Set.of());

        assertThat(address.getAddress().getHostAddress()).isEqualTo(expected);
        assertThat(address.getAddress().getAddress()).hasSize(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254", "::ffff:192.168.1.1"})
    void appliesIpv4RulesToMappedAddresses(final String host) {
        assertThatThrownBy(() -> ClprEndpointAddressPolicy.addressFor(host, PORT, Set.of()))
                .isInstanceOf(ClientException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "localhost",
                "peer.example",
                "8.8.8.8.example",
                "127.1",
                "2130706433",
                "0177.0.0.1",
                "0x7f000001",
                "008.008.008.008",
                "1.2.3.256",
                "-1.2.3.4",
                " 8.8.8.8",
                "8.8.8.8 ",
                "8.8.8.8:50211",
                "[2001:4860:4860::8888]",
                "2001:::1",
                "fe80::1%eth0",
                "fe80::1%1",
                "2001:4860::1%1"
            })
    void rejectsNamesAndMalformedLiterals(final String host) {
        assertThatThrownBy(() -> ClprEndpointAddressPolicy.addressFor(host, PORT, Set.of("0.0.0.0/0", "::/0")))
                .isInstanceOf(ClientException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 65536, Integer.MAX_VALUE})
    void rejectsInvalidPorts(final int port) {
        assertThatThrownBy(() -> ClprEndpointAddressPolicy.addressFor("8.8.8.8", port, Set.of()))
                .isInstanceOf(ClientException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 65535})
    void acceptsPortBoundaries(final int port) {
        assertThat(ClprEndpointAddressPolicy.addressFor("8.8.8.8", port, Set.of())
                        .getPort())
                .isEqualTo(port);
    }

    @ParameterizedTest
    @CsvSource({
        "10.1.2.3,10.1.2.3/32",
        "10.1.2.4,10.1.2.4/31",
        "10.1.2.5,10.1.2.4/31",
        "127.0.0.1,127.0.0.1/32",
        "169.254.169.254,169.254.169.254/32",
        "::ffff:10.1.2.3,10.1.2.3/32",
        "::1,::1/128",
        "fd00::4,fd00::4/127",
        "fd00::5,fd00::4/127",
        "fe80::1,fe80::1/128",
        "2001:db8::1,2001:db8::/32"
    })
    void acceptsAdditionalExactAndNarrowRanges(final String host, final String cidr) {
        final var address = ClprEndpointAddressPolicy.addressFor(host, PORT, Set.of(cidr));

        assertThat(address.isUnresolved()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "10.1.2.2,10.1.2.3/32", "10.1.2.3,10.1.2.4/31", "10.1.2.6,10.1.2.4/31", "fd00::3,fd00::4/127",
        "fd00::6,fd00::4/127", "::2,::1/128", "10.1.2.3,::/0", "fd00::1,0.0.0.0/0"
    })
    void rejectsAddressesOutsideAdditionalRanges(final String host, final String cidr) {
        assertThatThrownBy(() -> ClprEndpointAddressPolicy.addressFor(host, PORT, Set.of(cidr)))
                .isInstanceOf(ClientException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0.0.0.0",
                "0.0.0.1",
                "0.255.255.255",
                "224.0.0.0",
                "239.255.255.255",
                "240.0.0.0",
                "255.255.255.255",
                "::",
                "ff02::1",
                "::ffff:0.0.0.1",
                "::ffff:224.0.0.1",
                "::ffff:255.255.255.255"
            })
    void rejectsNonUnicastAddressesEvenWithAdditionalRanges(final String host) {
        assertThatThrownBy(() -> ClprEndpointAddressPolicy.addressFor(host, PORT, Set.of("0.0.0.0/0", "::/0")))
                .isInstanceOf(ClientException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "10.0.0.0",
                "/8",
                "10.0.0.0/",
                "10.0.0.0/8/9",
                "localhost/32",
                "10.0.0.0/-1",
                "10.0.0.0/33",
                "10.0.0.0/a",
                "::/-1",
                "::/129",
                "fd00::1%eth0/64",
                "[fd00::1]/128",
                "::ffff:10.0.0.1/128"
            })
    void rejectsInvalidAdditionalCidrEvenForPublicAddress(final String cidr) {
        assertThatThrownBy(() -> ClprEndpointAddressPolicy.addressFor("8.8.8.8", PORT, Set.of(cidr)))
                .isInstanceOf(ClientException.class);
    }

    @Test
    void requiresHostAndAdditionalRanges() {
        assertThatThrownBy(() -> ClprEndpointAddressPolicy.addressFor(null, PORT, Set.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ClprEndpointAddressPolicy.addressFor("8.8.8.8", PORT, null))
                .isInstanceOf(NullPointerException.class);
    }
}

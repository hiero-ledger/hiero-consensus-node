// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.swirlds.platform.network.ExternalIpAddress;
import com.swirlds.platform.network.Network;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NetworkTests {

    @ParameterizedTest
    @CsvSource({
        "10.0.0.1, true",
        "172.16.0.1, true",
        "172.31.255.255, true",
        "192.168.0.1, true",
        "192.169.0.1, false",
        "172.15.0.1, false",
        "172.32.0.1, false",
        "127.0.0.1, true"
    })
    @DisplayName("Validates that the private ip v4 is detected correctly")
    void isPrivateIPWithIPv4(String ip, boolean expected) throws Exception {
        // given:
        final InetAddress addr = Inet4Address.getByName(ip);

        // when:
        boolean result = Network.isPrivateIP(addr);

        // then:
        assertEquals(expected, result);
    }

    @ParameterizedTest
    @CsvSource({"fe80::1, true", "fd00::1, true", "fe7f::1, false", "fc00::1, false", "::1, true"})
    @DisplayName("Validates that the private ip v6 is detected correctly")
    void isPrivateIPWithIPv6(String ip, boolean expected) throws Exception {
        // given:
        final InetAddress addr = Inet6Address.getByName(ip);

        // when:
        boolean result = Network.isPrivateIP(addr);

        // then:
        assertEquals(expected, result);
    }

    @Test
    @DisplayName("Validates that the local ip is retrieved as internal ip")
    void getInternalIPAddressTest() {
        final String internalIp = Network.getInternalIPAddress();
        assertNotNull(internalIp);
        assertFalse(internalIp.isEmpty());
        assertNotEquals("127.0.0.1", internalIp);
    }

    @Test
    @DisplayName("No ip is found running as unit test")
    @Disabled("This test needs to be investigated")
    void getExternalIpAddressWithNoIpFound() {
        final ExternalIpAddress address = Network.getExternalIpAddress();
        assertEquals(ExternalIpAddress.NO_IP, address, "No IP should be found on unit test");
    }
}

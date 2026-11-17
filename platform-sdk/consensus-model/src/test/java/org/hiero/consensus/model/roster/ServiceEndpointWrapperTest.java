// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link ServiceEndpointWrapper} class.
 */
class ServiceEndpointWrapperTest {

    private static ServiceEndpoint endpoint(final Bytes ipAddressV4, final String domainName) {
        return ServiceEndpoint.newBuilder()
                .ipAddressV4(ipAddressV4)
                .domainName(domainName)
                .port(50211)
                .build();
    }

    /**
     * An endpoint that carries no IPv4 address is addressed by its domain name.
     */
    @Test
    void hostNameFallsBackToDomainName() {
        final ServiceEndpointWrapper wrapper = ServiceEndpointWrapper.of(endpoint(Bytes.EMPTY, "node1.hedera.com"));

        assertThat(wrapper.hostName()).isEqualTo("node1.hedera.com");
    }

    /**
     * An IPv4 address takes precedence over the domain name and is rendered in dotted decimal notation, with each
     * byte read as unsigned so that octets above 127 do not turn negative.
     */
    @Test
    void ipAddressIsRenderedAsUnsignedDottedDecimal() {
        final ServiceEndpointWrapper wrapper = ServiceEndpointWrapper.of(
                endpoint(Bytes.wrap(new byte[] {(byte) 192, (byte) 168, 0, (byte) 255}), "node1.hedera.com"));

        assertThat(wrapper.hostName()).isEqualTo("192.168.0.255");
    }

    /**
     * An address that is neither absent nor exactly four bytes long cannot be interpreted as IPv4 and is rejected.
     */
    @Test
    void nonIpV4AddressIsRejected() {
        final ServiceEndpoint tooShort = endpoint(Bytes.wrap(new byte[] {1, 2, 3}), "");
        final ServiceEndpoint ipV6 = endpoint(Bytes.wrap(new byte[16]), "");

        assertThatThrownBy(() -> ServiceEndpointWrapper.of(tooShort)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ServiceEndpointWrapper.of(ipV6)).isInstanceOf(IllegalArgumentException.class);
    }
}

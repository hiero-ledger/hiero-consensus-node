// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

import java.util.Objects;

/**
 * External Ip Address found by {@link Network#getExternalIpAddress()}
 */
public class ExternalIpAddress {

    public static final ExternalIpAddress NO_IP = new ExternalIpAddress(IpAddressStatus.NO_IP_FOUND, "");
    public static final ExternalIpAddress UPNP_DISABLED =
            new ExternalIpAddress(IpAddressStatus.ROUTER_UPNP_DISABLED, "");
    private final IpAddressStatus status;
    private final String ipAddress;

    ExternalIpAddress(final IpAddressStatus status, final String ipAddress) {
        this.status = status;
        this.ipAddress = ipAddress;
    }

    ExternalIpAddress(final String ipAddress) {
        this(IpAddressStatus.IP_FOUND, ipAddress);
    }

    public IpAddressStatus getStatus() {
        return status;
    }

    /**
     * If External IP address is found, then the address will be returned
     * in ipv4 or ipv6 format. Otherwise, an empty string
     *
     * @return External ip address or blank
     */
    public String getIpAddress() {
        return this.ipAddress;
    }

    /**
     * If External IP address is found, it is returned in ipv4/ipv6 format.
     * Otherwise, a string describing the status
     *
     * @return String representation of {@link ExternalIpAddress}
     */
    @Override
    public String toString() {
        if (this.status == IpAddressStatus.IP_FOUND) {
            return this.getIpAddress();
        }

        return this.status.name();
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        final ExternalIpAddress that = (ExternalIpAddress) other;
        return status == that.status && Objects.equals(ipAddress, that.ipAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, ipAddress);
    }
}

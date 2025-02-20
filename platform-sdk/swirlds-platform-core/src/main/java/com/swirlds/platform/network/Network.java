// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A set of static utility methods for finding all the local IP addresses associated with the local machine,
 * or checking whether a particular address is one of them. If the machine is behind a NATing router, then
 * it only deals with the local private addresses, not the external public addresses.
 */
public class Network {
    /** use this for all logging, as controlled by the optional data/log4j2.xml file */
    private static final Logger logger = LogManager.getLogger(Network.class);

    private static final String LOCALHOST = "127.0.0.1";

    private static Collection<InetAddress> ownAddresses;
    private static String ownAddress = null;
    private static ExternalIpAddress externalAddress = null;

    /**
     * All the utility methods are static, so the constructor is private, to prevent users from
     * instantiating the class.
     */
    private Network() {}

    /**
     * Determine whether a given IP address is an address of this local computer. It can be IPv4 or IPv6. If
     * there is Network Address Translation (NAT) occurring, then it only checks for the local, private
     * address, not any public address.
     *
     * @param addr
     * 		an IP address
     * @return true if addr is an address of this local computer
     */
    public static boolean isOwn(InetAddress addr) {
        return getOwnAddresses().contains(addr) || addr.isLoopbackAddress();
    }

    /**
     * Return the external IP address of this computer. If none has been found yet, return the empty
     * string. If the router doesn't have uPNP or NAT-PMP enabled, return a string asking the user to enable
     * it.
     *
     * @return the address, or empty string
     */
    @NonNull
    public static ExternalIpAddress getExternalIpAddress() {
        if (externalAddress == null) {
            final String ip = getOwnAddresses().stream()
                    .filter(Objects::nonNull)
                    .filter(address -> !isPrivateIP(address))
                    .map(InetAddress::getHostAddress)
                    .findFirst()
                    .orElse(null);
            externalAddress = ip == null ? ExternalIpAddress.NO_IP : new ExternalIpAddress(ip);
        }
        return externalAddress;
    }

    /**
     * Return the IP address of this computer. If there are several, it returns the first one, with a
     * preference for IPv4 over IPv6, and a preference for not using 128.0.0.1 if there is another IPv4
     * address.
     *
     * @return the IP address of this machine, as a string such as "111.222.33.44"
     */
    @Nullable
    public static String getInternalIPAddress() {
        if (ownAddress == null) {
            ownAddress = getOwnAddresses().stream()
                    .filter(a -> !LOCALHOST.equals(a.getHostAddress()))
                    .max(Comparator.comparing(Inet4Address.class::isInstance))
                    .map(InetAddress::getHostAddress)
                    .orElse(null);
        }

        return ownAddress;
    }

    /**
     * Get an array of all the local IP addresses that refer to this computer, both IPv4 and IPv6. If there
     * is Network Address Translation (NAT), then this only considers local, private addresses, and ignores
     * the external, public addresses.
     * <p>
     * If there is a need to show the user only a single address, the first in the array should be good. The
     * IPv4 addresses come before before the IPv6 addresses in the array. Within the IPv4 addresses, they
     * are are sorted alphabetically, except 127.0.0.1 is last.
     *
     * @return an array of all local addresses, sorted by IP version (4 or 6), then alphabetically.
     */
    public static Collection<InetAddress> getOwnAddresses() {
        if (ownAddresses == null) {
            ownAddresses = computeOwnAddresses();
        }

        return ownAddresses;
    }

    /**
     * Recompute the set of IP addresses for this computer, and their string representations.
     *
     * @return the set of addresses
     */
    private static Collection<InetAddress> computeOwnAddresses() {
        final Set<InetAddress> result = new HashSet<>();

        try {
            for (final Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                    interfaces.hasMoreElements(); ) {
                for (final Enumeration<InetAddress> addresses =
                                interfaces.nextElement().getInetAddresses();
                        addresses.hasMoreElements(); ) {
                    InetAddress addr = addresses.nextElement();
                    result.add(addr);
                }
            }
            return result;
        } catch (SocketException e) {
            logger.error(EXCEPTION.getMarker(), "Error getting own addresses", e);
            return Collections.emptySet();
        }
    }

    /**
     * Determine whether a given IP address is a private address, as defined by RFC 1918.
     * This works for both IPv4 and IPv6.
     *
     * @param addr the IP address to check
     *
     * @return true if addr is a private address
     */
    public static boolean isPrivateIP(@NonNull InetAddress addr) {
        Objects.requireNonNull(addr);
        final byte[] ip = addr.getAddress();

        // IPv4
        if (addr instanceof Inet4Address) {
            final int firstBlock = ip[0] & 0xFF;
            final int secondBlock = ip[1] & 0xFF;

            // @formatter:off
            return
            // Check for 10.x.x.x
            firstBlock == 10
                    ||
                    // Check for 172.16.x.x - 172.31.x.x
                    (firstBlock == 172 && secondBlock >= 16 && secondBlock <= 31)
                    ||
                    // Check for 192.168.x.x
                    (firstBlock == 192 && secondBlock == 168)
                    ||
                    // Check for localhost (starts with 127)
                    (firstBlock == 127);
            // @formatter:on
        }

        // IPv6
        else if (addr instanceof Inet6Address) {
            // @formatter:off
            return
            // Check for link-local (starts with fe80::)
            ((ip[0] & 0xFF) == 0xfe && (ip[1] & 0xC0) == 0x80)
                    ||
                    // Check for unique local (starts with fd00::)
                    (ip[0] & 0xFF) == 0xfd
                    // Loopback (starts with 0:0)
                    || ((ip[0] & 0xFF) == 0x00 && (ip[1] & 0xC0) == 0x00);
            // @formatter:on
        }
        return false;
    }
}

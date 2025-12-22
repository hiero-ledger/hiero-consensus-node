// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.address;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.regex.Pattern;

/**
 * A utility class for AddressBook functionality.
 * <p>
 * Each line in the config.txt address book contains the following comma separated elements:
 * <ul>
 *     <li>the keyword "address"</li>
 *     <li>node id</li>
 *     <li>nickname</li>
 *     <li>self name</li>
 *     <li>weight</li>
 *     <li>internal IP address</li>
 *     <li>internal port</li>
 *     <li>external IP address</li>
 *     <li>external port</li>
 *     <li>memo field (optional)</li>
 * </ul>
 * Example: `address, 22, node22, node22, 1, 10.10.11.12, 5060, 212.25.36.123, 5060, memo for node 22`
 */
public class AddressBookUtils {

    private static final Pattern IPV4_ADDRESS_PATTERN =
            Pattern.compile("^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.?\\b){4}$");

    private AddressBookUtils() {}


    /**
     * Given a host or ip and port, creates a {@link ServiceEndpoint} object with either an IP address or domain name
     * depending on the given hostOrIp.
     *
     * @param hostOrIp the hostname or ip address
     * @param port the port
     * @return the {@link ServiceEndpoint} object
     */
    public static ServiceEndpoint endpointFor(@NonNull final String hostOrIp, final int port) {
        final var builder = ServiceEndpoint.newBuilder().port(port);
        if (IPV4_ADDRESS_PATTERN.matcher(hostOrIp).matches()) {
            final var octets = hostOrIp.split("[.]");
            builder.ipAddressV4(Bytes.wrap(new byte[] {
                (byte) Integer.parseInt(octets[0]),
                (byte) Integer.parseInt(octets[1]),
                (byte) Integer.parseInt(octets[2]),
                (byte) Integer.parseInt(octets[3])
            }));
        } else {
            builder.domainName(hostOrIp);
        }
        return builder.build();
    }

    /**
     * Given a host, ip and port, creates a {@link ServiceEndpoint} object with an IP address, domain name
     * and port.
     *
     * @param ip the ip
     * @param host the host
     * @param port the port
     * @return the {@link ServiceEndpoint} object
     */
    public static ServiceEndpoint endpointFor(@NonNull final String ip, @NonNull final String host, final int port) {
        final var builder = ServiceEndpoint.newBuilder().port(port);
        if (IPV4_ADDRESS_PATTERN.matcher(ip).matches()) {
            final var octets = ip.split("[.]");
            builder.ipAddressV4(Bytes.wrap(new byte[] {
                (byte) Integer.parseInt(octets[0]),
                (byte) Integer.parseInt(octets[1]),
                (byte) Integer.parseInt(octets[2]),
                (byte) Integer.parseInt(octets[3])
            }));
        } else {
            throw new IllegalArgumentException("Cannot parse ip address from '" + ip + "'.");
        }
        builder.domainName(host);

        return builder.build();
    }
}

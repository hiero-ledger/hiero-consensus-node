// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.address;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.formatting.TextTable;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.hiero.consensus.model.roster.SimpleAddress;
import org.hiero.consensus.model.roster.SimpleAddresses;

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

    private static final String ADDRESS_KEYWORD = "address";
    private static final Pattern IPV4_ADDRESS_PATTERN =
            Pattern.compile("^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.?\\b){4}$");

    private AddressBookUtils() {}

    /**
     * Parses an address book from text in the form described by config.txt.  Comments are ignored.
     *
     * @param configTxtContent the config.txt compatible serialized address book to parse.
     * @return a parsed AddressBook.
     * @throws RuntimeException if any Address throws a ParseException when being parsed.
     */
    public static SimpleAddresses parseToSimpleAddresses(@NonNull final String configTxtContent) {
        Objects.requireNonNull(configTxtContent, "The configTxtContent must not be null.");
        final List<SimpleAddress> simpleAddresses = new ArrayList<>();

        Arrays.stream(configTxtContent.split("\\r?\\n"))
                .filter(line -> line.trim().startsWith(ADDRESS_KEYWORD))
                .forEach(line -> {
                    try {
                        final SimpleAddress simpleAddress = parseSimpleAddress(line.trim());
                        if (simpleAddress != null) {
                            simpleAddresses.add(simpleAddress);
                        }
                    } catch (ParseException e) {
                        throw new RuntimeException(e);
                    }
                });

        return new SimpleAddresses(simpleAddresses);
    }

    /**
     * Parse an address from a single line of text, if it exists.  Address lines may have comments which start with the
     * `#` character.  Comments are ignored.  Lines which are just comments return null.  If there is content prior to a
     * `#` character, parsing the address is attempted.  Any failure to generate an address will result in throwing a
     * parse exception.  The address parts are comma separated.   The format of text addresses prevent the use of `#`
     * and `,` characters in any of the text based fields, including the memo field.
     *
     * @param line the text to parse.
     * @return the parsed address or null if the line is a comment.
     * @throws ParseException if there is any problem with parsing the address.
     */
    private static SimpleAddress parseSimpleAddress(final String line) throws ParseException {
        final String[] textAndComment = line.split("#");
        if (textAndComment.length == 0
                || textAndComment[0] == null
                || textAndComment[0].trim().isEmpty()) {
            return null;
        }
        final String[] parts = textAndComment[0].split(",");
        if (parts.length < 9 || parts.length > 10) {
            throw new ParseException("Incorrect number of parts in the address line to parse correctly.", parts.length);
        }
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }

        final long nodeId;
        try {
            nodeId = Long.parseLong(parts[1]);
        } catch (final Exception e) {
            throw new ParseException("Cannot parse node id from '" + parts[1] + "'", 1);
        }
        final long weight;
        try {
            weight = Long.parseLong(parts[4]);
        } catch (NumberFormatException e) {
            throw new ParseException("Cannot parse value of weight from '" + parts[4] + "'", 4);
        }
        // FQDN Support: The original string value is preserved, whether it is an IP Address or a FQDN.
        final String internalHostname = parts[5];
        final int internalPort;
        try {
            internalPort = Integer.parseInt(parts[6]);
        } catch (NumberFormatException e) {
            throw new ParseException("Cannot parse ip port from '" + parts[6] + "'", 6);
        }
        // FQDN Support: The original string value is preserved, whether it is an IP Address or a FQDN.
        final String externalHostname = parts[7];
        final int externalPort;
        try {
            externalPort = Integer.parseInt(parts[8]);
        } catch (NumberFormatException e) {
            throw new ParseException("Cannot parse ip port from '" + parts[8] + "'", 8);
        }
        final List<ServiceEndpoint> serviceEndpoints =
                List.of(endpointFor(internalHostname, internalPort), endpointFor(externalHostname, externalPort));

        final String memoToUse = parts.length == 10 ? parts[9] : "";

        return new SimpleAddress(nodeId, weight, serviceEndpoints, memoToUse);
    }

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

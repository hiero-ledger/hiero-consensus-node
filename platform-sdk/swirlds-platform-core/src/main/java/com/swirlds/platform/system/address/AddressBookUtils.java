// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.address;

import static com.swirlds.platform.util.BootstrapUtils.detectSoftwareUpgrade;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.formatting.TextTable;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.address.AddressBookInitializer;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.text.ParseException;
import java.util.Objects;
import java.util.regex.Pattern;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.Address;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.roster.RosterRetriever;
import org.hiero.consensus.roster.RosterUtils;

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

    public static final String ADDRESS_KEYWORD = "address";
    private static final Pattern IPV4_ADDRESS_PATTERN =
            Pattern.compile("^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.?\\b){4}$");

    private AddressBookUtils() {}

    /**
     * Serializes an AddressBook to text in the form used by config.txt.
     *
     * @param addressBook the address book to serialize.
     * @return the config.txt compatible text representation of the address book.
     */
    @NonNull
    public static String addressBookConfigText(@NonNull final AddressBook addressBook) {
        Objects.requireNonNull(addressBook, "The addressBook must not be null.");
        final TextTable table = new TextTable().setBordersEnabled(false);
        for (final Address address : addressBook) {
            final String memo = address.getMemo();
            final boolean hasMemo = !memo.trim().isEmpty();
            final boolean hasInternalIpv4 = address.getHostnameInternal() != null;
            final boolean hasExternalIpv4 = address.getHostnameExternal() != null;
            table.addRow(
                    "address,",
                    address.getNodeId() + ",",
                    address.getNickname() + ",",
                    address.getSelfName() + ",",
                    address.getWeight() + ",",
                    (hasInternalIpv4 ? address.getHostnameInternal() : "") + ",",
                    address.getPortInternal() + ",",
                    (hasExternalIpv4 ? address.getHostnameExternal() : "") + ",",
                    address.getPortExternal() + (hasMemo ? "," : ""),
                    memo);
        }
        return table.render();
    }

    /**
     * Parses an address book from text in the form described by config.txt.  Comments are ignored.
     *
     * @param addressBookText the config.txt compatible serialized address book to parse.
     * @return a parsed AddressBook.
     * @throws ParseException if any Address throws a ParseException when being parsed.
     */
    @NonNull
    public static AddressBook parseAddressBookText(@NonNull final String addressBookText) throws ParseException {
        Objects.requireNonNull(addressBookText, "The addressBookText must not be null.");
        final AddressBook addressBook = new AddressBook();
        for (final String line : addressBookText.split("\\r?\\n")) {
            final String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()
                    || trimmedLine.startsWith("#")
                    || trimmedLine.startsWith("swirld")
                    || trimmedLine.startsWith("app")) {
                continue;
            }
            if (trimmedLine.startsWith(ADDRESS_KEYWORD)) {
                final Address address = parseAddressText(trimmedLine);
                if (address != null) {
                    addressBook.add(address);
                }
            } else if (trimmedLine.startsWith("nextNodeId")) {
                // As of release 0.56, nextNodeId is not used and ignored.
                // CI/CD pipelines need to be updated to remove this field from files.
                // Future Work: remove this case and hard fail when nextNodeId is no longer present in CI/CD pipelines.
            } else {
                throw new ParseException(
                        "The line [%s] does not start with `%s`."
                                .formatted(line.substring(0, Math.min(line.length(), 30)), ADDRESS_KEYWORD),
                        0);
            }
        }
        return addressBook;
    }

    /**
     * Parse an address from a single line of text, if it exists.  Address lines may have comments which start with the
     * `#` character.  Comments are ignored.  Lines which are just comments return null.  If there is content prior to a
     * `#` character, parsing the address is attempted.  Any failure to generate an address will result in throwing a
     * parse exception.  The address parts are comma separated.   The format of text addresses prevent the use of `#`
     * and `,` characters in any of the text based fields, including the memo field.
     *
     * @param addressText the text to parse.
     * @return the parsed address or null if the line is a comment.
     * @throws ParseException if there is any problem with parsing the address.
     */
    @Nullable
    public static Address parseAddressText(@NonNull final String addressText) throws ParseException {
        Objects.requireNonNull(addressText, "The addressText must not be null.");
        // lines may have comments which start with the first # character.
        final String[] textAndComment = addressText.split("#");
        if (textAndComment.length == 0
                || textAndComment[0] == null
                || textAndComment[0].trim().isEmpty()) {
            return null;
        }
        final String[] parts = addressText.split(",");
        if (parts.length < 9 || parts.length > 10) {
            throw new ParseException("Incorrect number of parts in the address line to parse correctly.", parts.length);
        }
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        if (!parts[0].equals(ADDRESS_KEYWORD)) {
            throw new ParseException("The address line must start with 'address' and not '" + parts[0] + "'", 0);
        }
        final NodeId nodeId;
        try {
            nodeId = NodeId.of(Long.parseLong(parts[1]));
        } catch (final Exception e) {
            throw new ParseException("Cannot parse node id from '" + parts[1] + "'", 1);
        }
        final String nickname = parts[2];
        final String selfname = parts[3];
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
        final String memoToUse = parts.length == 10 ? parts[9] : "";

        return new Address(
                nodeId,
                nickname,
                selfname,
                weight,
                internalHostname,
                internalPort,
                externalHostname,
                externalPort,
                null,
                null,
                memoToUse);
    }

    /**
     * Given a host and port, creates a {@link ServiceEndpoint} object with either an IP address or domain name
     * depending on the given host.
     *
     * @param host the host
     * @param port the port
     * @return the {@link ServiceEndpoint} object
     */
    public static ServiceEndpoint endpointFor(@NonNull final String host, final int port) {
        final var builder = ServiceEndpoint.newBuilder().port(port);
        if (IPV4_ADDRESS_PATTERN.matcher(host).matches()) {
            final var octets = host.split("[.]");
            builder.ipAddressV4(Bytes.wrap(new byte[] {
                (byte) Integer.parseInt(octets[0]),
                (byte) Integer.parseInt(octets[1]),
                (byte) Integer.parseInt(octets[2]),
                (byte) Integer.parseInt(octets[3])
            }));
        } else {
            builder.domainName(host);
        }
        return builder.build();
    }

    /**
     * Initializes the address book from the configuration and platform saved state.
     *
     * @param selfId               the node ID of the current node
     * @param version              the software version of the current node
     * @param initialState         the initial state of the platform
     * @param bootstrapAddressBook the bootstrap address book
     * @param platformContext      the platform context
     */
    public static void initializeAddressBook(
            @NonNull final NodeId selfId,
            @NonNull final SemanticVersion version,
            @NonNull final ReservedSignedState initialState,
            @NonNull final AddressBook bootstrapAddressBook,
            @NonNull final PlatformContext platformContext,
            @NonNull final ConsensusStateEventHandler<?> consensusStateEventHandler,
            @NonNull final PlatformStateFacade platformStateFacade) {
        final boolean softwareUpgrade = detectSoftwareUpgrade(version, initialState.get(), platformStateFacade);
        // Initialize the address book from the configuration and platform saved state.
        final AddressBookInitializer addressBookInitializer = new AddressBookInitializer(
                selfId,
                softwareUpgrade,
                initialState.get(),
                bootstrapAddressBook.copy(),
                platformContext,
                consensusStateEventHandler,
                platformStateFacade);
        final State state = initialState.get().getState();

        if (addressBookInitializer.hasAddressBookChanged()) {
            if (addressBookInitializer.getPreviousAddressBook() != null) {
                // We cannot really "update" the previous roster because we don't know the round number
                // at which it became active. And we shouldn't do that anyway because under normal circumstances
                // the RosterService tracks the roster history correctly. However, since we're given a non-null
                // previous AddressBook, and per the current implementation we know it comes from the state,
                // we might as well validate this fact here just to ensure the update is correct.
                final Roster previousRoster =
                        RosterRetriever.buildRoster(addressBookInitializer.getPreviousAddressBook());
                final long round = platformStateFacade.roundOf(state);
                if (!previousRoster.equals(RosterRetriever.retrieveActive(state, round))
                        && !previousRoster.equals(RosterRetriever.retrievePreviousRoster(state))) {
                    throw new IllegalStateException(
                            "The previousRoster in the AddressBookInitializer doesn't match either the active or previous roster in state."
                                    + " AddressBookInitializer previousRoster = " + RosterUtils.toString(previousRoster)
                                    + ", state currentRoster = "
                                    + RosterUtils.toString(RosterRetriever.retrieveActive(state, round))
                                    + ", state previousRoster = "
                                    + RosterUtils.toString(RosterRetriever.retrievePreviousRoster(state)));
                }
            }

            // The active roster is already initialized when creating a genesis state, so only set it here
            // if it's not for the genesis state (round 0)
            if (initialState.get().getRound() > 0) {
                RosterUtils.setActiveRoster(
                        state,
                        RosterRetriever.buildRoster(addressBookInitializer.getCurrentAddressBook()),
                        platformStateFacade.roundOf(state) + 1);
            }
        }

        // At this point the initial state must have the current address book set.  If not, something is wrong.
        final long round = platformStateFacade.roundOf(state);
        final AddressBook addressBook = RosterUtils.buildAddressBook(RosterRetriever.retrieveActive(state, round));
        if (addressBook == null) {
            throw new IllegalStateException("The current address book of the initial state is null.");
        }
    }
}

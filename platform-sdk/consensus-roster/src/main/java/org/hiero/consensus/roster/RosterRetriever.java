// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.utility.Pair;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableSingletonState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.cert.CertificateEncodingException;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.hiero.consensus.model.roster.Address;
import org.hiero.consensus.model.roster.AddressBook;

/**
 * A utility class to help retrieve a Roster instance from the state.
 */
public final class RosterRetriever {

    private RosterRetriever() {}

    private static final String ROSTER_SERVICE = "RosterService";
    private static final String IP_ADDRESS_COMPONENT_REGEX = "(\\d{1,2}|(?:0|1)\\d{2}|2[0-4]\\d|25[0-5])";
    private static final Pattern IP_ADDRESS_PATTERN =
            Pattern.compile("^%N\\.%N\\.%N\\.%N$".replace("%N", IP_ADDRESS_COMPONENT_REGEX));

    /**
     * Retrieve the previous Roster from the state, or null if the roster has never changed yet.
     * <p>
     * The previous roster is the one that has been in use prior to the current active roster,
     * i.e. prior to the one returned by the retrieveActiveOrGenesisRoster() method.
     *
     * @param state a state to fetch the previous roster from
     * @return the previous roster, or null
     */
    @Nullable
    public static Roster retrievePreviousRoster(@NonNull final State state) {
        final ReadableSingletonState<RosterState> rosterState =
                state.getReadableStates(ROSTER_SERVICE).getSingleton(RosterStateId.ROSTER_STATE_STATE_ID);
        final List<RoundRosterPair> roundRosterPairs =
                requireNonNull(rosterState.get()).roundRosterPairs();

        if (roundRosterPairs.size() < 2) {
            return null;
        }

        return retrieveInternal(state, roundRosterPairs.get(1).activeRosterHash());
    }

    /**
     * Retrieve an active Roster from the state for a given round.
     * <p>
     * This method first checks the RosterState/RosterMap entities,
     * and if they contain a roster for the given round, then returns it.
     * If there's not a roster defined for a given round, then null is returned.
     *
     * @return an active Roster for the given round of the state, or null
     */
    @Nullable
    public static Roster retrieveActive(@NonNull final State state, final long round) {
        return retrieveInternal(state, getActiveRosterHash(state, round));
    }

    /**
     * Returns the candidate roster from the state, if it exists.
     * @param state the state
     * @return the candidate roster, or null if it doesn't exist
     */
    public static @Nullable Roster retrieveCandidate(@NonNull final State state) {
        return retrieveInternal(state, getCandidateRosterHash(state));
    }

    /**
     * Retrieve a hash of the active roster for a given round of the state,
     * or null if the roster is unknown for that round.
     * A roster may be unknown if the RosterState hasn't been populated yet,
     * or the given round of the state predates the implementation of the Roster.
     *
     * @param state a state
     * @param round a round number
     * @return a Bytes object with the roster hash, or null
     */
    @Nullable
    public static Bytes getActiveRosterHash(@NonNull final State state, final long round) {
        final ReadableSingletonState<RosterState> rosterState =
                state.getReadableStates(ROSTER_SERVICE).getSingleton(RosterStateId.ROSTER_STATE_STATE_ID);
        // replace with binary search when/if the list size becomes unreasonably large (100s of entries or more)
        final var roundRosterPairs = requireNonNull(rosterState.get()).roundRosterPairs();
        for (final var roundRosterPair : roundRosterPairs) {
            if (roundRosterPair.roundNumber() <= round) {
                return roundRosterPair.activeRosterHash();
            }
        }
        return null;
    }

    /**
     * Retrieves the hash of the current candidate roster in the given state.
     * @param state a state
     * @return the hash of the candidate roster
     */
    public static @NonNull Bytes getCandidateRosterHash(@NonNull final State state) {
        final var rosterState =
                state.getReadableStates(ROSTER_SERVICE).<RosterState>getSingleton(RosterStateId.ROSTER_STATE_STATE_ID);
        return requireNonNull(rosterState.get()).candidateRosterHash();
    }

    /**
     * Builds a RosterEntry out of a given Address.
     * @param address an Address from AddressBook
     * @return a RosterEntry
     */
    @NonNull
    public static RosterEntry buildRosterEntry(@NonNull final Address address) {
        try {
            // There's code, especially in tests, that creates AddressBooks w/o any certificates/keys
            // (because it would be time-consuming, and these tests don't use the keys anyway.)
            // So we need to be able to handle this situation here:
            final Bytes cert = address.getSigCert() == null
                    ? Bytes.EMPTY
                    : Bytes.wrap(address.getSigCert().getEncoded());
            return RosterEntry.newBuilder()
                    .nodeId(address.getNodeId().id())
                    .weight(address.getWeight())
                    .gossipCaCertificate(cert)
                    .gossipEndpoint(Stream.of(
                                    Pair.of(address.getHostnameExternal(), address.getPortExternal()),
                                    Pair.of(address.getHostnameInternal(), address.getPortInternal()))
                            .filter(pair -> pair.left() != null && !pair.left().isBlank() && pair.right() != 0)
                            .distinct()
                            .map(pair -> {
                                final Matcher matcher = IP_ADDRESS_PATTERN.matcher(pair.left());

                                if (!matcher.matches()) {
                                    return ServiceEndpoint.newBuilder()
                                            .domainName(pair.left())
                                            .port(pair.right())
                                            .build();
                                }

                                try {
                                    return ServiceEndpoint.newBuilder()
                                            .ipAddressV4(Bytes.wrap(new byte[] {
                                                (byte) Integer.parseInt(matcher.group(1)),
                                                (byte) Integer.parseInt(matcher.group(2)),
                                                (byte) Integer.parseInt(matcher.group(3)),
                                                (byte) Integer.parseInt(matcher.group(4)),
                                            }))
                                            .port(pair.right())
                                            .build();
                                } catch (NumberFormatException e) {
                                    throw new InvalidAddressBookException(e);
                                }
                            })
                            .toList())
                    .build();
        } catch (CertificateEncodingException e) {
            throw new InvalidAddressBookException(e);
        }
    }

    /**
     * Builds a Roster object out of a given AddressBook object.
     *
     * @param addressBook an AddressBook
     * @return a Roster
     */
    @Nullable
    public static Roster buildRoster(@Nullable final AddressBook addressBook) {
        if (addressBook == null) {
            return null;
        }

        return Roster.newBuilder()
                .rosterEntries(addressBook.getNodeIdSet().stream()
                        .map(addressBook::getAddress)
                        .map(RosterRetriever::buildRosterEntry)
                        .sorted(Comparator.comparing(RosterEntry::nodeId))
                        .toList())
                .build();
    }

    @Nullable
    private static Roster retrieveInternal(@NonNull final State state, @Nullable final Bytes activeRosterHash) {
        if (activeRosterHash != null) {
            final ReadableKVState<ProtoBytes, Roster> rosterMap =
                    state.getReadableStates(ROSTER_SERVICE).get(RosterStateId.ROSTERS_STATE_ID);
            return rosterMap.get(new ProtoBytes(activeRosterHash));
        }
        return null;
    }
}

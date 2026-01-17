// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeMetadata;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.base.crypto.CryptographyException;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.Address;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.roster.internal.PbjRecordHasher;

/**
 * A utility class to help use Rooster and RosterEntry instances.
 */
public final class RosterUtils {
    private static final PbjRecordHasher PBJ_RECORD_HASHER = new PbjRecordHasher();

    private RosterUtils() {}

    /**
     * Formats a "node name" for a given node id, e.g. "node1" for nodeId == 0.
     * This name can be used for logging purposes, or to support code that
     * uses strings to identify nodes.
     *
     * @param nodeId a node id
     * @return a "node name"
     */
    @NonNull
    public static String formatNodeName(final long nodeId) {
        return "node" + (nodeId + 1);
    }

    /**
     * Formats a "node name" for a given node id, e.g. "node1" for nodeId == 0.
     * This name can be used for logging purposes, or to support code that
     * uses strings to identify nodes.
     *
     * @param nodeId a node id
     * @return a "node name"
     */
    @NonNull
    public static String formatNodeName(final @NonNull NodeId nodeId) {
        return formatNodeName(nodeId.id());
    }

    /**
     * Fetch the gossip certificate from a given RosterEntry.  If it cannot be parsed successfully, return null.
     *
     * @param entry a RosterEntry
     * @return a gossip certificate
     */
    public static X509Certificate fetchGossipCaCertificate(@NonNull final RosterEntry entry) {
        try {
            return CryptoUtils.decodeCertificate(entry.gossipCaCertificate().toByteArray());
        } catch (final CryptographyException e) {
            return null;
        }
    }

    /**
     * Check if the given rosters change at most the weights of the nodes.
     * @param from the previous roster
     * @param to the new roster
     * @return true if the rosters are weight rotations, false otherwise
     */
    public static boolean isWeightRotation(@NonNull final Roster from, @NonNull final Roster to) {
        requireNonNull(from, "from");
        requireNonNull(to, "to");
        final Set<Long> fromNodes =
                from.rosterEntries().stream().map(RosterEntry::nodeId).collect(Collectors.toSet());
        final Set<Long> toNodes =
                to.rosterEntries().stream().map(RosterEntry::nodeId).collect(Collectors.toSet());
        return fromNodes.equals(toNodes);
    }

    /**
     * Fetch a hostname (or a string with an IPv4 address) of a ServiceEndpoint
     * at a given index in a given RosterEntry.
     *
     * @param entry a RosterEntry
     * @param index an index of the ServiceEndpoint
     * @return a string with a hostname or ip address
     */
    public static String fetchHostname(@NonNull final RosterEntry entry, final int index) {
        final ServiceEndpoint serviceEndpoint = entry.gossipEndpoint().get(index);
        final Bytes ipAddressV4 = serviceEndpoint.ipAddressV4();
        final long length = ipAddressV4.length();
        if (length == 0) {
            return serviceEndpoint.domainName();
        }
        if (length == 4) {
            return HapiUtils.asReadableIp(ipAddressV4);
        }
        throw new IllegalArgumentException("Invalid IP address: " + ipAddressV4 + " in RosterEntry: " + entry);
    }

    /**
     * Fetch a port number of a ServiceEndpoint
     * at a given index in a given RosterEntry.
     *
     * @param entry a RosterEntry
     * @param index an index of the ServiceEndpoint
     * @return a port number
     */
    public static int fetchPort(@NonNull final RosterEntry entry, final int index) {
        final ServiceEndpoint serviceEndpoint = entry.gossipEndpoint().get(index);
        return serviceEndpoint.port();
    }

    /**
     * Create a Hash object for a given Roster instance.
     *
     * @param roster a roster
     * @return its Hash
     */
    @NonNull
    public static Hash hash(@NonNull final Roster roster) {
        return PBJ_RECORD_HASHER.hash(roster, Roster.PROTOBUF);
    }

    /**
     * Build a map from a long nodeId to a RosterEntry for a given Roster.
     *
     * @param roster a roster
     * @return {@code Map<Long, RosterEntry>}
     */
    @Nullable
    public static Map<Long, RosterEntry> toMap(@Nullable final Roster roster) {
        if (roster == null) {
            return null;
        }
        return roster.rosterEntries().stream().collect(Collectors.toMap(RosterEntry::nodeId, Function.identity()));
    }

    /**
     * Build a map from a long nodeId to an index of the node in the roster entries list.
     * If code needs to perform this lookup only once, then use the getIndex() instead.
     *
     * @param roster a roster
     * @return {@code Map<Long, Integer>}
     */
    public static Map<Long, Integer> toIndicesMap(@NonNull final Roster roster) {
        return IntStream.range(0, roster.rosterEntries().size())
                .boxed()
                .collect(Collectors.toMap(i -> roster.rosterEntries().get(i).nodeId(), Function.identity()));
    }

    /**
     * Return an index of a RosterEntry with a given node id.
     * If code needs to perform this operation often, then use the toIndicesMap() instead.
     *
     * @param roster a Roster
     * @param nodeId a node id
     * @return an index, or -1 if not found
     */
    public static int getIndex(@NonNull final Roster roster, final long nodeId) {
        for (int i = 0; i < roster.rosterEntries().size(); i++) {
            if (roster.rosterEntries().get(i).nodeId() == nodeId) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Compute the total weight of a Roster which is a sum of weights of all the RosterEntries.
     *
     * @param roster a roster
     * @return the total weight
     */
    public static long computeTotalWeight(@NonNull final Roster roster) {
        return roster.rosterEntries().stream().mapToLong(RosterEntry::weight).sum();
    }

    /**
     * Returns a RosterEntry with a given nodeId by simply iterating all entries,
     * w/o building a temporary map.
     * <p>
     * Useful for one-off look-ups. If code needs to look up multiple entries by NodeId,
     * then the code should use the RosterUtils.toMap() method and keep the map instance
     * for the look-ups.
     *
     * @param roster a roster
     * @param nodeId a node id
     * @return a RosterEntry
     * @throws RosterEntryNotFoundException if RosterEntry is not found in Roster
     */
    public static RosterEntry getRosterEntry(@NonNull final Roster roster, final long nodeId) {
        final RosterEntry entry = getRosterEntryOrNull(roster, nodeId);
        if (entry != null) {
            return entry;
        }

        throw new RosterEntryNotFoundException("No RosterEntry with nodeId: " + nodeId + " in Roster: " + roster);
    }

    /**
     * Returns a NodeId with a given index
     *
     * @param roster a roster
     * @param nodeIndex an index of the node
     * @return a NodeId
     * @throws IndexOutOfBoundsException if the index does not exist in the roster
     */
    @NonNull
    public static NodeId getNodeId(@NonNull final Roster roster, final int nodeIndex) {
        return NodeId.of(requireNonNull(roster).rosterEntries().get(nodeIndex).nodeId());
    }

    /**
     * Return a potentially cached NodeId instance for a given {@link RosterEntry}.
     * The caller MUST NOT mutate the returned object even though the NodeId class is technically mutable.
     * If the caller needs to mutate the instance, then it must use the regular NodeId(long) constructor instead.
     *
     * @param rosterEntry a {@code RosterEntry}
     * @return a NodeId instance
     */
    public static NodeId getNodeId(@NonNull final RosterEntry rosterEntry) {
        return NodeId.of(rosterEntry.nodeId());
    }

    /**
     * Retrieves the roster entry that matches the specified node ID, returning null if one does not exist.
     * <p>
     * Useful for one-off look-ups. If code needs to look up multiple entries by NodeId, then the code should use the
     * {@link #toMap(Roster)} method and keep the map instance for the look-ups.
     *
     * @param roster the roster to search
     * @param nodeId the ID of the node to retrieve
     * @return the found roster entry that matches the specified node ID, else null
     */
    public static RosterEntry getRosterEntryOrNull(@NonNull final Roster roster, final long nodeId) {
        requireNonNull(roster, "roster");

        for (final RosterEntry entry : roster.rosterEntries()) {
            if (entry.nodeId() == nodeId) {
                return entry;
            }
        }

        return null;
    }

    /**
     * Count the number of RosterEntries with non-zero weight.
     *
     * @param roster a roster
     * @return the number of RosterEntries with non-zero weight
     */
    public static int getNumberWithWeight(@NonNull final Roster roster) {
        return (int) roster.rosterEntries().stream()
                .map(RosterEntry::weight)
                .filter(w -> w != 0)
                .count();
    }

    /**
     * Creates the Roster History to be used by Platform.
     *
     * @param state the state containing the active roster history.
     * @return the roster history if roster store contains active rosters, otherwise NullPointerException is thrown.
     */
    @NonNull
    public static RosterHistory createRosterHistory(@NonNull final State state) {
        final ReadableRosterStore rosterStore =
                new ReadableRosterStoreImpl(state.getReadableStates(RosterStateId.SERVICE_NAME));
        final List<RoundRosterPair> roundRosterPairs = rosterStore.getRosterHistory();
        final Map<Bytes, Roster> rosterMap = new HashMap<>();
        for (final RoundRosterPair pair : roundRosterPairs) {
            rosterMap.put(pair.activeRosterHash(), Objects.requireNonNull(rosterStore.get(pair.activeRosterHash())));
        }
        return new RosterHistory(roundRosterPairs, rosterMap);
    }

    /**
     * Sets the active Roster in a given State.
     *
     * @param state a state to set a Roster in
     * @param roster a Roster to set as active
     * @param round a round number since which the roster is considered active
     */
    public static void setActiveRoster(@NonNull final State state, @NonNull final Roster roster, final long round) {
        final WritableStates writableStates = state.getWritableStates(RosterStateId.SERVICE_NAME);
        final WritableRosterStore writableRosterStore = new WritableRosterStore(writableStates);
        writableRosterStore.putActiveRoster(roster, round);
        ((CommittableWritableStates) writableStates).commit();
    }

    /**
     * Formats a human-readable Roster representation, currently using its JSON codec,
     * or returns {@code null} if the given roster object is null.
     * @param roster a roster to format
     * @return roster JSON string, or null
     */
    @Nullable
    public static String toString(@Nullable final Roster roster) {
        return roster == null ? null : Roster.JSON.toJSON(roster);
    }

    /**
     * Build an Address object out of a given RosterEntry object.
     *
     * @param entry a RosterEntry
     * @return an Address
     * @deprecated To be removed once AddressBook to Roster refactoring is complete.
     */
    @Deprecated(forRemoval = true)
    @NonNull
    public static Address buildAddress(@NonNull final RosterEntry entry) {
        Address address = new Address();

        address = address.copySetNodeId(NodeId.of(entry.nodeId()));
        address = address.copySetWeight(entry.weight());

        X509Certificate sigCert;
        try {
            sigCert = CryptoUtils.decodeCertificate(entry.gossipCaCertificate().toByteArray());
        } catch (final CryptographyException e) {
            // Malformed or missing gossip certificates are nullified.
            // https://github.com/hashgraph/hedera-services/issues/16648
            sigCert = null;
        }
        address = address.copySetSigCert(sigCert);

        if (entry.gossipEndpoint().size() > 0) {
            address = address.copySetHostnameExternal(RosterUtils.fetchHostname(entry, 0));
            address = address.copySetPortExternal(RosterUtils.fetchPort(entry, 0));

            if (entry.gossipEndpoint().size() > 1) {
                address = address.copySetHostnameInternal(RosterUtils.fetchHostname(entry, 1));
                address = address.copySetPortInternal(RosterUtils.fetchPort(entry, 1));
            } else {
                // There's code in the app implementation that relies on both the external and internal endpoints at
                // once.
                // That code used to fetch the AddressBook from the Platform for some reason.
                // Since Platform only knows about the Roster now, we have to support both the endpoints
                // in this reverse conversion here.
                // Ideally, the app code should manage its AddressBook on its own and should never fetch it from
                // Platform directly.
                address = address.copySetHostnameInternal(RosterUtils.fetchHostname(entry, 0));
                address = address.copySetPortInternal(RosterUtils.fetchPort(entry, 0));
            }
        }

        final String name = RosterUtils.formatNodeName(entry.nodeId());
        address = address.copySetSelfName(name).copySetNickname(name);

        return address;
    }

    /**
     * Build an AddressBook object out of a given Roster object.
     * Returns null if the input roster is null.
     * @param roster a Roster
     * @return an AddressBook
     * @deprecated To be removed once AddressBook to Roster refactoring is complete.
     */
    @Deprecated(forRemoval = true)
    @Nullable
    public static AddressBook buildAddressBook(@Nullable final Roster roster) {
        if (roster == null) {
            return null;
        }

        AddressBook addressBook = new AddressBook();

        for (final RosterEntry entry : roster.rosterEntries()) {
            addressBook = addressBook.add(buildAddress(entry));
        }

        return addressBook;
    }

    /**
     * Build a Roster object out of a given {@link Network} address book.
     * @param network a network
     * @return a Roster
     */
    public static @NonNull Roster rosterFrom(@NonNull final Network network) {
        return new Roster(network.nodeMetadata().stream()
                .map(NodeMetadata::rosterEntryOrThrow)
                .toList());
    }
}

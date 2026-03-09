// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeMetadata;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.base.crypto.CryptographyException;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.internal.PbjRecordHasher;

/**
 * A utility class to help use Rooster and RosterEntry instances.
 */
public final class RosterUtils {
    private static final PbjRecordHasher PBJ_RECORD_HASHER = new PbjRecordHasher();

    private RosterUtils() {}

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
     * The fixed 12-byte SPKI header for Ed25519 public keys (OID 1.3.101.112).
     * Prepending this to a raw 32-byte Ed25519 key yields a valid X.509 SubjectPublicKeyInfo encoding.
     */
    private static final byte[] ED25519_SPKI_HEADER = {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    /**
     * Fetch the Ed25519 event signing public key from a given RosterEntry.
     * Returns null if the field is empty or cannot be parsed.
     *
     * @param entry a RosterEntry
     * @return an Ed25519 PublicKey, or null if not available
     */
    @Nullable
    public static PublicKey fetchEventSigningPublicKey(@NonNull final RosterEntry entry) {
        final var rawKey = entry.eventSigningPublicKey();
        if (rawKey == null || rawKey.length() != 32) {
            return null;
        }
        try {
            // Build SPKI encoding: 12-byte fixed header + 32-byte raw Ed25519 public key
            final byte[] spki = new byte[ED25519_SPKI_HEADER.length + 32];
            System.arraycopy(ED25519_SPKI_HEADER, 0, spki, 0, ED25519_SPKI_HEADER.length);
            rawKey.getBytes(0, spki, ED25519_SPKI_HEADER.length, 32);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
        } catch (final Exception e) {
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

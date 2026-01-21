// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.platform.internal.Deserializer;
import com.swirlds.platform.internal.Serializer;
import com.swirlds.platform.network.PeerInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.SocketException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;

/**
 * This is a collection of static utility methods, such as for comparing and deep cloning of arrays.
 */
public final class Utilities {

    private Utilities() {}

    // ----------------------------------------------------------
    // read from DataInputStream and
    // write to DataOutputStream

    /**
     * Writes a list to the stream serializing the objects with the supplied method
     *
     * @param list
     * 		the list to be serialized
     * @param stream
     * 		the stream to write to
     * @param serializer
     * 		the method used to write the object
     * @param <T>
     * 		the type of object being written
     * @throws IOException
     * 		thrown if there are any problems during the operation
     */
    @Deprecated
    public static <T> void writeList(List<T> list, SerializableDataOutputStream stream, Serializer<T> serializer)
            throws IOException {
        if (list == null) {
            stream.writeInt(-1);
            return;
        }
        stream.writeInt(list.size());
        for (T t : list) {
            serializer.serialize(t, stream);
        }
    }

    /**
     * Reads a list from the stream deserializing the objects with the supplied method
     *
     * @param stream
     * 		the stream to read from
     * @param listSupplier
     * 		a method that supplies the list to add to
     * @param deserializer
     * 		a method used to deserialize the objects
     * @param <T>
     * 		the type of object contained in the list
     * @return a list that was read from the stream, can be null if that was written
     * @throws IOException
     * 		thrown if there are any problems during the operation
     */
    @Deprecated
    public static <T> List<T> readList(
            SerializableDataInputStream stream, Supplier<List<T>> listSupplier, Deserializer<T> deserializer)
            throws IOException {
        int listSize = stream.readInt();
        if (listSize < 0) {
            return null;
        }
        List<T> list = listSupplier.get();
        for (int i = 0; i < listSize; i++) {
            list.add(deserializer.deserialize(stream));
        }
        return list;
    }

    /**
     * Convert the given long to bytes, big endian.
     *
     * @param n
     * 		the long to convert
     * @return a big-endian representation of n as an array of Long.BYTES bytes
     */
    public static byte[] toBytes(long n) {
        byte[] bytes = new byte[Long.BYTES];
        toBytes(n, bytes, 0);
        return bytes;
    }

    /**
     * Convert the given long to bytes, big endian, and put them into the array, starting at index start
     *
     * @param bytes
     * 		the array to hold the Long.BYTES bytes of result
     * @param n
     * 		the long to convert to bytes
     * @param start
     * 		the bytes are written to Long.BYTES elements of the array, starting with this index
     */
    public static void toBytes(long n, byte[] bytes, int start) {
        for (int i = start + Long.BYTES - 1; i >= start; i--) {
            bytes[i] = (byte) n;
            n >>>= 8;
        }
    }

    /**
     * convert the given byte array to a long
     *
     * @param b
     * 		the byte array to convert (at least 8 bytes)
     * @return the long that was represented by the array
     */
    public static long toLong(byte[] b) {
        return toLong(b, 0);
    }

    /**
     * convert part of the given byte array to a long, starting with index start
     *
     * @param b
     * 		the byte array to convert
     * @param start
     * 		the index of the first byte (most significant byte) of the 8 bytes to convert
     * @return the long
     */
    public static long toLong(byte[] b, int start) {
        long result = 0;
        for (int i = start; i < start + Long.BYTES; i++) {
            result <<= 8;
            result |= b[i] & 0xFF;
        }
        return result;
    }

    /**
     * if it is or caused by SocketException,
     * we should log it with SOCKET_EXCEPTIONS marker
     *
     * @param ex
     * @return return true if it is a SocketException or is caused by SocketException;
     * 		return false otherwise
     */
    public static boolean isOrCausedBySocketException(final Throwable ex) {
        return isRootCauseSuppliedType(ex, SocketException.class);
    }

    /**
     * @param e
     * 		the exception to check
     * @return true if the cause is an IOException
     */
    public static boolean isCausedByIOException(final Exception e) {
        return isRootCauseSuppliedType(e, IOException.class);
    }

    /**
     * Unwraps a Throwable and checks the root cause
     *
     * @param t
     * 		the throwable to unwrap
     * @param type
     * 		the type to check against
     * @return true if the root cause matches the supplied type
     */
    public static boolean isRootCauseSuppliedType(final Throwable t, final Class<? extends Throwable> type) {
        if (t == null) {
            return false;
        }
        Throwable cause = t;
        // get to the root cause
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return type.isInstance(cause);
    }

    /**
     * Checks all nesting of causes for any instance of the supplied type.
     *
     * @param throwable
     * 		the throwable to unwrap
     * @param type
     * 		the type to check against
     * @return true if any of the causes matches the supplied type, false otherwise.
     */
    public static boolean hasAnyCauseSuppliedType(
            @NonNull final Throwable throwable, @NonNull final Class<? extends Throwable> type) {
        Throwable cause = throwable;
        // check all causes
        while (cause != null) {
            if (type.isInstance(cause)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Create a list of PeerInfos from the roster. The list will contain information about all peers but not us.
     * Peers without valid gossip certificates are not included.
     *
     * @param roster
     * 		the roster to create the list from
     * @param selfId
     * 		our ID
     * @return a list of PeerInfo
     */
    public static @NonNull List<PeerInfo> createPeerInfoList(
            @NonNull final Roster roster, @NonNull final NodeId selfId) {
        Objects.requireNonNull(roster);
        Objects.requireNonNull(selfId);
        return roster.rosterEntries().stream()
                .filter(entry -> entry.nodeId() != selfId.id())
                // Only include peers with valid gossip certificates
                // https://github.com/hashgraph/hedera-services/issues/16648
                .filter(entry -> CryptoUtils.checkCertificate((RosterUtils.fetchGossipCaCertificate(entry))))
                .map(Utilities::toPeerInfo)
                .toList();
    }

    /**
     * Converts single roster entry to PeerInfo, which is more abstract class representing information about possible node connection
     * @param entry data to convert
     * @return PeerInfo with extracted hostname, port and certificate for remote host
     */
    public static @NonNull PeerInfo toPeerInfo(@NonNull RosterEntry entry) {
        Objects.requireNonNull(entry);
        return new PeerInfo(
                NodeId.of(entry.nodeId()),
                // Assume that the first ServiceEndpoint describes the external hostname,
                // which is the same order in which RosterRetriever.buildRoster(AddressBook) lists them.
                Objects.requireNonNull(RosterUtils.fetchHostname(entry, 0)),
                RosterUtils.fetchPort(entry, 0),
                Objects.requireNonNull(RosterUtils.fetchGossipCaCertificate(entry)));
    }
}

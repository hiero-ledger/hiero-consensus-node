// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.EOFException;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.common.FastCopyable;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hiero.base.crypto.Signature;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.model.node.NodeId;

/**
 * Signatures of the hash of a state.
 */
public class SigSet implements FastCopyable, Iterable<NodeId>, SelfSerializable {
    private static final long CLASS_ID = 0x756d0ee945226a92L;

    private static final FieldDefinition FIELD_SIGNATURES =
            new FieldDefinition("signatures", FieldType.MESSAGE, true, true, false, 1);
    private static final FieldDefinition FIELD_NODE_ID =
            new FieldDefinition("nodeId", FieldType.UINT64, false, true, false, 1);
    private static final FieldDefinition FIELD_SIGNATURE_TYPE =
            new FieldDefinition("signatureType", FieldType.UINT32, false, true, false, 2);
    private static final FieldDefinition FIELD_SIGNATURE_BYTES =
            new FieldDefinition("signatureBytes", FieldType.BYTES, false, true, false, 3);

    /**
     * The maximum allowed signature count. Used to prevent serialization DOS attacks.
     */
    public static final int MAX_SIGNATURE_COUNT = 1024;

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
        public static final int MIGRATE_TO_SERIALIZABLE = 2;
        public static final int CLEANUP = 3;
        public static final int SELF_SERIALIZABLE_NODE_ID = 4;
    }

    private final Map<NodeId, Signature> signatures = new HashMap<>();

    /**
     * Zero arg constructor.
     */
    public SigSet() {}

    /**
     * Copy constructor.
     *
     * @param that the sig set to copy
     */
    private SigSet(final SigSet that) {
        this.signatures.putAll(that.signatures);
    }

    /**
     * Add a signature to the sigset. Does not validate the signature.
     *
     * @param nodeId    the ID of the node that provided the signature
     * @param signature the signature to add
     */
    public void addSignature(@NonNull final NodeId nodeId, @NonNull final Signature signature) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(signature, "signature must not be null");
        signatures.put(nodeId, signature);
    }

    /**
     * Remove a signature from the sigset.
     *
     * @param nodeId the ID of the signature to remove
     */
    public void removeSignature(@NonNull final NodeId nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        signatures.remove(nodeId);
    }

    /**
     * Get the signature for the given node ID, or null if there is no signature for the requested node.
     *
     * @param nodeId the ID of the node
     * @return a signature for the node, or null if there is no signature for the node
     */
    @Nullable
    public Signature getSignature(@NonNull final NodeId nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        return signatures.get(nodeId);
    }

    /**
     * Check if this sigset has a signature for a given node.
     *
     * @param nodeId the node ID in question
     * @return true if a signature from this node is present
     */
    public boolean hasSignature(@NonNull final NodeId nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        return signatures.containsKey(nodeId);
    }

    /**
     * Get an iterator that walks over the set of nodes that have signed the state.
     */
    @Override
    @NonNull
    public Iterator<NodeId> iterator() {
        final Iterator<NodeId> iterator = signatures.keySet().iterator();

        // Wrap the iterator so that it can't be used to modify the SigSet.
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public NodeId next() {
                return iterator.next();
            }
        };
    }

    /**
     * Get a list of all signing nodes. This list is safe to modify without affecting the SigSet.
     *
     * @return a list of all signing nodes
     */
    @NonNull
    public List<NodeId> getSigningNodes() {
        return new ArrayList<>(signatures.keySet());
    }

    /**
     * Get the number of signatures.
     *
     * @return the number of signatures
     */
    public int size() {
        return signatures.size();
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    @NonNull
    public SigSet copy() {
        return new SigSet(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.CLEANUP;
    }

    /**
     * Serialize this object to a PBJ stream.
     *
     * @param out the stream to write to
     * @throws IOException if an I/O error occurs
     */
    public void serialize(@NonNull final WritableStreamingData out) throws IOException {
        final List<NodeId> sortedIds = getSigningNodes().stream().sorted().toList();

        for (final NodeId nodeId : sortedIds) {
            final Signature signature = signatures.get(nodeId);
            final Bytes signatureBytes = signature.getBytes();

            // Write the tag for the repeated signatures field
            ProtoWriterTools.writeTag(out, FIELD_SIGNATURES);

            // Each signature is a message, so we need to calculate its size
            int msgSize = calculateSignatureMsgSize(nodeId, signature);

            out.writeVarInt(msgSize, false);

            ProtoWriterTools.writeTag(out, FIELD_NODE_ID);
            out.writeVarLong(nodeId.id(), false);

            ProtoWriterTools.writeTag(out, FIELD_SIGNATURE_TYPE);
            out.writeVarInt(signature.getType().ordinal(), false);

            ProtoWriterTools.writeBytes(out, FIELD_SIGNATURE_BYTES, signatureBytes);
        }
    }

    private static int calculateSignatureMsgSize(NodeId nodeId, Signature signature) {
        int msgSize = 0;
        msgSize += ProtoWriterTools.sizeOfTag(FIELD_NODE_ID, ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG);
        msgSize += ProtoWriterTools.sizeOfVarInt64(nodeId.id());
        msgSize += ProtoWriterTools.sizeOfTag(FIELD_SIGNATURE_TYPE, ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG);
        msgSize += ProtoWriterTools.sizeOfVarInt32(signature.getType().ordinal());
        msgSize += ProtoWriterTools.sizeOfTag(FIELD_SIGNATURE_BYTES, ProtoConstants.WIRE_TYPE_DELIMITED);
        msgSize += ProtoWriterTools.sizeOfVarInt32((int) signature.getBytes().length());
        msgSize += (int) signature.getBytes().length();
        return msgSize;
    }

    /**
     * Deserialize this object from a PBJ stream.
     *
     * @param in the stream to read from
     * @throws IOException if an I/O error occurs
     */
    public void deserialize(@NonNull final ReadableStreamingData in) throws IOException {
        signatures.clear();
        final long endPosition = in.limit();
        while (in.position() < endPosition) {
            final int tag;
            try {
                tag = in.readVarInt(false);
            } catch (EOFException e) {
                // no more data, exit loop
                break;
            }
            final int fieldNum = tag >> TAG_FIELD_OFFSET;
            if (fieldNum == FIELD_SIGNATURES.number()) {
                final int msgSize = in.readVarInt(false);
                final long limit = in.position() + msgSize;

                long nodeIdVal = -1;
                int sigTypeOrdinal = -1;
                Bytes sigBytes = null;

                while (in.position() < limit) {
                    final int innerTag;
                    try {
                        innerTag = in.readVarInt(false);
                    } catch (EOFException e) {
                        break;
                    }
                    final int innerFieldNum = innerTag >> TAG_FIELD_OFFSET;
                    if (innerFieldNum == FIELD_NODE_ID.number()) {
                        nodeIdVal = in.readVarLong(false);
                    } else if (innerFieldNum == FIELD_SIGNATURE_TYPE.number()) {
                        sigTypeOrdinal = in.readVarInt(false);
                    } else if (innerFieldNum == FIELD_SIGNATURE_BYTES.number()) {
                        final int bytesSize = in.readVarInt(false);
                        final byte[] bytes = new byte[bytesSize];
                        in.readBytes(bytes);
                        sigBytes = Bytes.wrap(bytes);
                    } else {
                        // Skip unknown inner field
                        final int wireType = innerTag & ProtoConstants.TAG_WIRE_TYPE_MASK;
                        skipField(in, wireType);
                    }
                }

                if (nodeIdVal != -1 && sigTypeOrdinal != -1 && sigBytes != null) {
                    final NodeId nodeId = NodeId.of(nodeIdVal);
                    final org.hiero.base.crypto.SignatureType type = org.hiero.base.crypto.SignatureType.from(
                            sigTypeOrdinal, org.hiero.base.crypto.SignatureType.RSA);
                    signatures.put(nodeId, new Signature(type, sigBytes));
                }

                if (signatures.size() > MAX_SIGNATURE_COUNT) {
                    throw new IOException(
                            "Signature count of " + signatures.size() + " exceeds maximum of " + MAX_SIGNATURE_COUNT);
                }
            } else {
                // Unknown field, skip it
                final int wireType = tag & ProtoConstants.TAG_WIRE_TYPE_MASK;
                skipField(in, wireType);
            }
        }
    }

    private static void skipField(@NonNull final ReadableStreamingData in, final int wireType) throws IOException {
        if (wireType == ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal()) {
            in.readVarLong(false);
        } else if (wireType == ProtoConstants.WIRE_TYPE_FIXED_64_BIT.ordinal()) {
            in.skip(8);
        } else if (wireType == ProtoConstants.WIRE_TYPE_DELIMITED.ordinal()) {
            final int length = in.readVarInt(false);
            in.skip(length);
        } else if (wireType == ProtoConstants.WIRE_TYPE_FIXED_32_BIT.ordinal()) {
            in.skip(4);
        } else {
            throw new IOException("Unsupported wire type: " + wireType);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeInt(signatures.size());

        final List<NodeId> sortedIds = new ArrayList<>(signatures.size());
        signatures.keySet().stream().sorted().forEachOrdered(sortedIds::add);

        for (final NodeId nodeId : sortedIds) {
            out.writeSerializable(nodeId, false);
            signatures.get(nodeId).serialize(out, false);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        final int signatureCount = in.readInt();
        if (signatureCount > MAX_SIGNATURE_COUNT) {
            throw new IOException(
                    "Signature count of " + signatureCount + " exceeds maximum of " + MAX_SIGNATURE_COUNT);
        }

        for (int index = 0; index < signatureCount; index++) {
            final NodeId nodeId;
            if (version < ClassVersion.SELF_SERIALIZABLE_NODE_ID) {
                nodeId = NodeId.of(in.readLong());
            } else {
                nodeId = in.readSerializable(false, NodeId::new);
            }
            final Signature signature = Signature.deserialize(in, false);
            signatures.put(nodeId, signature);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.SELF_SERIALIZABLE_NODE_ID;
    }
}

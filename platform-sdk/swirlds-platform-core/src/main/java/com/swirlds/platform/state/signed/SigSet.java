// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

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

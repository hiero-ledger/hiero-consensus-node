// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.proof.tree;

import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.CryptographyProvider;
import com.swirlds.common.merkle.MerkleLeaf;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.security.MessageDigest;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * A node in a state proof tree containing a payload. Each payload corresponds to a merkle leaf in the original
 * state that is being proven by the state proof.
 */
public class StateProofPayload extends AbstractStateProofNode implements SelfSerializable {
    private static final long CLASS_ID = 0xd21870ecd467b717L;
    private static final Cryptography CRYPTOGRAPHY = CryptographyProvider.getInstance();

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    private MerkleLeaf payload;
    private boolean initialized = false;

    /**
     * Zero arg constructor required by the serialization framework.
     */
    public StateProofPayload() {}

    /**
     * Construct a new leaf node with the given payload (i.e. a merkle leaf we want to prove).
     *
     * @param payload the payload
     * @throws IllegalArgumentException if the payload is not hashed
     */
    public StateProofPayload(@Nullable final MerkleLeaf payload) {
        if (payload != null && payload.getHash() == null) {
            throw new IllegalArgumentException("Payload must be hashed");
        }
        this.payload = payload;
        initialized = true;
    }

    /**
     * Get the payload merkle leaf.
     *
     * @return the payload merkle leaf
     */
    @NonNull
    public MerkleLeaf getPayload() {
        return payload;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void computeHashableBytes(@NonNull final MessageDigest digest) {
        if (!initialized) {
            throw new IllegalStateException("StateProofPayload has not been properly initialized");
        }
        if (payload.getHash() == null) {
            CRYPTOGRAPHY.digestSync(payload);
        }
        setHashableBytes(payload.getHash().copyToByteArray());
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
        return ClassVersion.ORIGINAL;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(@NonNull final SerializableDataOutputStream out) throws IOException {
        out.writeSerializable(payload, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(@NonNull final SerializableDataInputStream in, final int version) throws IOException {
        payload = in.readSerializable();
        initialized = true;
    }
}

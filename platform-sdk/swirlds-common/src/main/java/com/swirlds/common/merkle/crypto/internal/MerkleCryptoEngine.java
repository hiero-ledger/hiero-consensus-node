// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.crypto.internal;

import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.CryptographyException;
import com.swirlds.common.crypto.CryptographyProvider;
import com.swirlds.common.crypto.config.CryptoConfig;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.hash.MerkleHashBuilder;
import com.swirlds.logging.legacy.LogMarker;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import org.hiero.consensus.model.crypto.Hash;

public class MerkleCryptoEngine implements MerkleCryptography {
    /**
     * The cryptography instance used to compute digests for merkle trees.
     */
    private static final Cryptography CRYPTOGRAPHY = CryptographyProvider.getInstance();

    /**
     * The digest provider instance that is used to generate hashes of MerkleInternal objects.
     */
    private final MerkleInternalDigestProvider merkleInternalDigestProvider;

    /**
     * The merkle provider used to compute digests for merkle trees.
     */
    private final MerkleHashBuilder merkleHashBuilder;

    /**
     * Create a new merkle crypto engine.
     *
     * @param settings provides settings for cryptography
     */
    public MerkleCryptoEngine(final CryptoConfig settings) {
        this.merkleInternalDigestProvider = new MerkleInternalDigestProvider();
        this.merkleHashBuilder = new MerkleHashBuilder(this, settings.computeCpuDigestThreadCount());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash digestTreeSync(final MerkleNode root) {
        return merkleHashBuilder.digestTreeSync(root);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<Hash> digestTreeAsync(final MerkleNode root) {
        return merkleHashBuilder.digestTreeAsync(root);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash digestSync(final MerkleInternal node, final List<Hash> childHashes, final boolean setHash) {
        try {
            final Hash hash = merkleInternalDigestProvider.compute(node, childHashes, Cryptography.DEFAULT_DIGEST_TYPE);
            if (setHash) {
                node.setHash(hash);
            }
            return hash;
        } catch (final NoSuchAlgorithmException e) {
            throw new CryptographyException(e, LogMarker.EXCEPTION);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash digestSync(final MerkleInternal node, boolean setHash) {
        List<Hash> childHashes = new ArrayList<>(node.getNumberOfChildren());
        for (int childIndex = 0; childIndex < node.getNumberOfChildren(); childIndex++) {
            MerkleNode child = node.getChild(childIndex);
            if (child == null) {
                childHashes.add(Cryptography.NULL_HASH);
            } else {
                childHashes.add(child.getHash());
            }
        }
        return digestSync(node, childHashes, setHash);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash digestSync(MerkleLeaf leaf) {
        return CRYPTOGRAPHY.digestSync(leaf);
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.crypto.internal;

import static com.swirlds.common.merkle.utility.MerkleConstants.MERKLE_DIGEST_TYPE;

import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.CryptographyException;
import com.swirlds.common.crypto.DigestType;
import com.swirlds.common.crypto.Hash;
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

public class MerkleCryptoEngine implements MerkleCryptography {

    /**
     * The digest provider instance that is used to generate hashes of MerkleInternal objects.
     */
    private final MerkleInternalDigestProvider merkleInternalDigestProvider;

    /**
     * The merkle provider used to compute digests for merkle trees.
     */
    private final MerkleHashBuilder merkleHashBuilder;

    private final Cryptography basicCryptoEngine;

    /**
     * Create a new merkle crypto engine.
     *
     * @param cryptography
     * 		provides cryptographic primitives
     * @param settings
     * 		provides settings for cryptography
     */
    public MerkleCryptoEngine(final Cryptography cryptography, final CryptoConfig settings) {
        basicCryptoEngine = cryptography;
        this.merkleInternalDigestProvider = new MerkleInternalDigestProvider();
        this.merkleHashBuilder = new MerkleHashBuilder(this, cryptography, settings.computeCpuDigestThreadCount());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash digestTreeSync(final MerkleNode root, final DigestType digestType) {
        return merkleHashBuilder.digestTreeSync(root);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<Hash> digestTreeAsync(final MerkleNode root, final DigestType digestType) {
        return merkleHashBuilder.digestTreeAsync(root);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash digestSync(final MerkleInternal node, final List<Hash> childHashes, final boolean setHash) {
        try {
            final Hash hash = merkleInternalDigestProvider.compute(node, childHashes, MERKLE_DIGEST_TYPE);
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
    public Hash digestSync(final MerkleInternal node, final DigestType digestType, boolean setHash) {
        List<Hash> childHashes = new ArrayList<>(node.getNumberOfChildren());
        for (int childIndex = 0; childIndex < node.getNumberOfChildren(); childIndex++) {
            MerkleNode child = node.getChild(childIndex);
            if (child == null) {
                childHashes.add(basicCryptoEngine.getNullHash(digestType));
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
    public Hash digestSync(MerkleLeaf leaf, DigestType digestType) {
        return basicCryptoEngine.digestSync(leaf, digestType);
    }
}

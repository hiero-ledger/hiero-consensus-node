// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.crypto.internal;

import static com.swirlds.logging.legacy.LogMarker.TESTING_EXCEPTIONS;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.exceptions.IllegalChildHashException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.HashBuilder;
import org.hiero.base.crypto.engine.CachingOperationProvider;

/**
 * A {@link CachingOperationProvider} implementation that is capable of computing a hash for a supplied {@link
 * MerkleInternal} instance.
 */
public class MerkleInternalDigestProvider
        extends CachingOperationProvider<MerkleInternal, List<Hash>, Hash, HashBuilder, DigestType> {

    private static final Logger logger = LogManager.getLogger(MerkleInternalDigestProvider.class);

    /**
     * {@inheritDoc}
     */
    @Override
    protected HashBuilder handleAlgorithmRequired(DigestType algorithmType) throws NoSuchAlgorithmException {
        return new HashBuilder(MessageDigest.getInstance(algorithmType.algorithmName()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Hash handleItem(
            final HashBuilder hashBuilder,
            final DigestType algorithmType,
            final MerkleInternal node,
            List<Hash> childHashes) {

        hashBuilder.reset();

        hashBuilder.update(node.getClassId());
        hashBuilder.update(node.getVersion());
        for (int index = 0; index < childHashes.size(); index++) {
            final Hash childHash = childHashes.get(index);

            if (childHash == null || childHash.getBytes() == null) {
                final MerkleNode childNode = node.getChild(index);
                final String msg = String.format(
                        "Child has an unexpected null hash "
                                + "[ parentClass = '%s', childClass = '%s', childRoute = %s ]",
                        node.getClass().getName(), childNode.getClass().getName(), childNode.getRoute());

                logger.trace(TESTING_EXCEPTIONS.getMarker(), msg);
                throw new IllegalChildHashException(msg);
            }

            hashBuilder.update(childHash);
        }

        return hashBuilder.build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash compute(final MerkleInternal node, final List<Hash> childHashes, final DigestType algorithmType)
            throws NoSuchAlgorithmException {
        return handleItem(loadAlgorithm(algorithmType), algorithmType, node, childHashes);
    }
}

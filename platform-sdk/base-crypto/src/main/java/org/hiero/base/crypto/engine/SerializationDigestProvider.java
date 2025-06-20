// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.engine;

import com.swirlds.logging.legacy.LogMarker;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.hiero.base.crypto.CryptographyException;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.HashingOutputStream;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * A {@link CachingOperationProvider} capable of computing hashes for {@link SelfSerializable} objects by hashing the
 * serialized bytes of the object.
 */
public class SerializationDigestProvider
        extends CachingOperationProvider<SelfSerializable, Void, byte[], HashingOutputStream, DigestType> {
    /**
     * {@inheritDoc}
     */
    @Override
    protected HashingOutputStream handleAlgorithmRequired(final DigestType algorithmType)
            throws NoSuchAlgorithmException {
        return new HashingOutputStream(MessageDigest.getInstance(algorithmType.algorithmName()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected byte[] handleItem(
            final HashingOutputStream algorithm,
            final DigestType algorithmType,
            final SelfSerializable item,
            final Void optionalData) {
        algorithm.resetDigest(); // probably not needed, just to be safe
        try (SerializableDataOutputStream out = new SerializableDataOutputStream(algorithm)) {
            out.writeSerializable(item, true);
            out.flush();

            return algorithm.getDigest();
        } catch (IOException ex) {
            throw new CryptographyException(ex, LogMarker.EXCEPTION);
        }
    }
}

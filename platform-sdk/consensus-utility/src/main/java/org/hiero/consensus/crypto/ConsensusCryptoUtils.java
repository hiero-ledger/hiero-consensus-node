// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.PublicKey;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.CryptographyException;

/**
 * Utility methods for cryptographic operations
 */
public class ConsensusCryptoUtils {

    private static final Logger logger = LogManager.getLogger();

    private ConsensusCryptoUtils() {}

    /**
     * See {@link SignatureVerifier#verifySignature(Bytes, Bytes, PublicKey)}
     */
    public static boolean verifySignature(
            @NonNull final Bytes data, @NonNull final Bytes signature, @NonNull final PublicKey publicKey) {
        Objects.requireNonNull(data);
        Objects.requireNonNull(signature);
        Objects.requireNonNull(publicKey);
        try {
            return SigningFactory.createVerifier(publicKey).verify(data, signature);
        } catch (final CryptographyException e) {
            logger.error(LogMarker.EXCEPTION.getMarker(), "Exception occurred while validating a signature:", e);
            return false;
        }
    }
}

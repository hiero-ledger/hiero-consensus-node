// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.crypto;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.crypto.engine.CryptoEngine;
import com.swirlds.common.threading.locks.AutoClosableLock;
import com.swirlds.common.threading.locks.Locks;
import com.swirlds.common.threading.locks.locked.Locked;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Static holder of the {@link Cryptography} instance.
 *
 * @deprecated This class will be removed once the {@link com.swirlds.common.context.PlatformContext} is used.
 */
@Deprecated(forRemoval = true)
public final class CryptographyHolder {

    private static final Logger logger = LogManager.getLogger(CryptographyHolder.class);

    private static final AutoClosableLock lock = Locks.createAutoLock();

    /**
     * The singleton instance of the {@link Cryptography} interface.
     */
    private static Cryptography cryptography;

    /**
     * Private constructor to prevent instantiation.
     */
    private CryptographyHolder() {
        throw new UnsupportedOperationException();
    }

    /**
     * Setup the config holder.
     *
     * @param cryptography the {@link CryptoEngine} to use
     */
    public static void set(@NonNull final Cryptography cryptography) {
        try (final Locked ignored = lock.lock()) {
            CryptographyHolder.cryptography = cryptography;
        }
    }

    /**
     * Setup cryptography. Only needed to support unit tests that do not go through the proper setup procedures.
     */
    private static void init() {
        // This exception is intended to intentionally fail validators for deployments that do not set up
        // cryptography correctly. This won't cause a problem during unit tests, which are the biggest offender
        // w.r.t. not setting up cryptography correctly.
        logger.error(EXCEPTION.getMarker(), "CryptographyHolder not initialized, using default config");

        cryptography = CryptographyFactory.create();
    }

    /**
     * Getter for the {@link Cryptography} singleton.
     *
     * @return the {@link Cryptography} singleton
     */
    @NonNull
    public static Cryptography get() {
        try (final Locked ignored = lock.lock()) {
            if (cryptography == null) {
                init();
            }
            return cryptography;
        }
    }
}

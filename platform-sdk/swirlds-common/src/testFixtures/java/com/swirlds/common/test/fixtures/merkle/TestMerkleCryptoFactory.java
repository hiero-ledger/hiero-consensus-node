// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle;

import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.locks.AutoClosableLock;
import org.hiero.base.concurrent.locks.Locks;
import org.hiero.base.concurrent.locks.locked.Locked;
import org.hiero.base.crypto.config.CryptoConfig;

/**
 * Factory for creating {@link MerkleCryptography} instances for use in tests.
 */
public class TestMerkleCryptoFactory {

    private static final Logger logger = LogManager.getLogger(TestMerkleCryptoFactory.class);

    /**
     * Internal lock
     */
    private static final AutoClosableLock lock = Locks.createAutoLock();

    /**
     * the single {@link MerkleCryptography} instance
     */
    private static MerkleCryptography merkleCryptography;

    private TestMerkleCryptoFactory() {}

    /**
     * Setup cryptography. Only needed to support unit tests that do not go through the proper setup procedures.
     */
    private static void init() {
        final Configuration defaultConfiguration = ConfigurationBuilder.create()
                .withConfigDataType(CryptoConfig.class)
                .build();
        merkleCryptography = MerkleCryptographyFactory.create(defaultConfiguration);
    }

    /**
     * Set the {@link MerkleCryptography} singleton.
     *
     * @param merkleCryptography the {@link MerkleCryptography} to use
     */
    public static void set(@NonNull final MerkleCryptography merkleCryptography) {
        try (final Locked ignored = lock.lock()) {
            TestMerkleCryptoFactory.merkleCryptography = merkleCryptography;
        }
    }

    /**
     * Getter for the {@link MerkleCryptography} singleton.
     *
     * @return the {@link MerkleCryptography} singleton
     */
    public static MerkleCryptography getInstance() {
        try (final Locked ignored = lock.lock()) {
            if (merkleCryptography == null) {
                init();
            }
            return merkleCryptography;
        }
    }
}

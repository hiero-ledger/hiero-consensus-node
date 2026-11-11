// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import static com.hedera.statevalidation.util.ParallelProcessingUtils.VALIDATOR_FORK_JOIN_POOL;

import com.hedera.statevalidation.util.StateUtils;
import com.hedera.statevalidation.validator.util.ValidationException;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.TaskPerNodeFullRehasher;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;

/**
 * Validator that performs full rehash of the state and compares against the original hash.
 *
 * <p>The rehash is done over the leaves of the snapshot as it was loaded from disk, before any
 * service migrations are applied to the state. Migrations (e.g. on a version upgrade) may change
 * leaves in the in-memory cache of the mutable state copy, but they are never flushed to the data
 * source, so reading leaves directly from the data source gives the original snapshot leaves.
 *
 * <p>This validator runs independently (not through the data pipeline) because it uses
 * tasks for parallel tree traversal.
 * @see Validator
 */
public class RehashValidator implements Validator {

    private static final Logger logger = LogManager.getLogger(RehashValidator.class);

    public static final String REHASH_GROUP = "rehash";

    private VirtualMap virtualMap;
    private Hash originalHash;

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getGroup() {
        return REHASH_GROUP;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getName() {
        // Intentionally same as group, as its the most appropriate name for this validator
        return REHASH_GROUP;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(@NonNull final VirtualMapState state) {
        this.virtualMap = state.getRoot();
        this.originalHash = StateUtils.getOriginalStateHash();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validate() {
        final DigestType loadedDigestType = virtualMap.getDataSource().getLoadedHashDigestType();
        if (loadedDigestType != Cryptography.DEFAULT_DIGEST_TYPE) {
            // The original hash was computed by a full rehash at load time, since stored hashes
            // were of a different digest type. The check below only verifies rehash determinism
            // then. The snapshot hash in its original digest type is checked by RootHashValidator
            logger.info(
                    "Snapshot hash digest type is {}, but the default is {}. The original hash was recalculated"
                            + " from leaves at load time",
                    loadedDigestType,
                    Cryptography.DEFAULT_DIGEST_TYPE);
        }

        final long startTime = System.currentTimeMillis();
        final Hash computedHash;

        try {
            computedHash = rehashSnapshotLeaves(virtualMap, Cryptography.DEFAULT_DIGEST_TYPE);
        } catch (final Exception e) {
            throw new ValidationException(REHASH_GROUP, "Unexpected exception: " + e.getMessage(), e);
        }

        if (!java.util.Objects.equals(originalHash, computedHash)) {
            throw new ValidationException(
                    getName(), String.format("Expected <%s> but was <%s>", originalHash, computedHash));
        }

        logger.debug("It took {} ms to rehash the state", System.currentTimeMillis() - startTime);
    }

    /**
     * Performs a full rehash of the snapshot leaves stored in the virtual map data source using the
     * given digest type. Leaves are read from the data source directly, bypassing the virtual node
     * cache, so any changes made to the state after it was loaded, e.g. by service migrations, are
     * not taken into account.
     *
     * @param virtualMap the virtual map loaded from the snapshot
     * @param digestType the digest type to use for hashing
     * @return the root hash, or {@code null} if the snapshot is empty
     */
    static Hash rehashSnapshotLeaves(@NonNull final VirtualMap virtualMap, @NonNull final DigestType digestType) {
        final VirtualDataSource dataSource = virtualMap.getDataSource();
        final long firstLeafPath = dataSource.getFirstLeafPath();
        final long lastLeafPath = dataSource.getLastLeafPath();
        logger.debug(
                "Doing full {} rehash for the snapshot path range: {} - {}", digestType, firstLeafPath, lastLeafPath);
        return TaskPerNodeFullRehasher.hash(
                VALIDATOR_FORK_JOIN_POOL,
                digestType,
                firstLeafPath,
                lastLeafPath,
                path -> {
                    try {
                        return dataSource.loadLeafRecord(path);
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                },
                TaskPerNodeFullRehasher.Listener.NO_OP,
                virtualMap.getVirtualMapConfig().fullRehashTimeoutMs());
    }
}

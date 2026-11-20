// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import static com.hedera.statevalidation.validator.RehashValidator.REHASH_GROUP;
import static org.hiero.consensus.platformstate.PlatformStateUtils.HASH_INFO_TEMPLATE;

import com.hedera.statevalidation.util.ConfigUtils;
import com.hedera.statevalidation.util.StateUtils;
import com.hedera.statevalidation.validator.util.ValidationException;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.MerkleHasher;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.Mnemonics;

/**
 * Validator that validates the root hash of the state by comparing
 * the root hash line against a reference file.
 *
 * <p>The reference file is written when the snapshot is saved, so it's compared with the hash
 * of the snapshot as it was loaded, before any service migrations are applied to the state. If
 * the snapshot was saved with a different hash digest type than the current default (e.g. SHA-384
 * before the switch to SHA-256), the snapshot hash is recalculated from leaves using the original
 * digest type.
 *
 * <p>This validator runs independently (not through the data pipeline) because it
 * requires reading an external hash info file for comparison.
 * @see Validator
 */
public class RootHashValidator implements Validator {

    private static final Logger logger = LogManager.getLogger(RootHashValidator.class);

    public static final String ROOT_HASH_NAME = "rootHash";

    /**
     * The file name containing the expected hash info.
     */
    public static final String HASH_INFO_FILE_NAME = "hashInfo.txt";

    private VirtualMap virtualMap;
    private String expectedRootHashLine;

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
        return ROOT_HASH_NAME;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(@NonNull final VirtualMapState state) {
        this.virtualMap = state.getRoot();

        // Read hash info file
        final Path hashInfoPath = Path.of(ConfigUtils.STATE_DIR, HASH_INFO_FILE_NAME);
        try (BufferedReader reader = Files.newBufferedReader(hashInfoPath, StandardCharsets.UTF_8)) {
            final String content = reader.lines().collect(Collectors.joining("\n"));
            this.expectedRootHashLine = Arrays.asList(content.split("\n")).getFirst();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validate() {
        final DigestType loadedDigestType = virtualMap.getDataSource().getLoadedHashDigestType();
        final Hash snapshotHash;
        if (loadedDigestType == Cryptography.DEFAULT_DIGEST_TYPE) {
            snapshotHash = StateUtils.getOriginalStateHash();
        } else {
            logger.info(
                    "Snapshot hash digest type is {}, but the default is {}. Recalculating snapshot hash using {}",
                    loadedDigestType,
                    Cryptography.DEFAULT_DIGEST_TYPE,
                    loadedDigestType);
            try {
                final Hash rehashed = RehashValidator.rehashSnapshotLeaves(virtualMap, loadedDigestType);
                snapshotHash = (rehashed != null) ? rehashed : MerkleHasher.emptyRootHash(loadedDigestType);
            } catch (final Exception e) {
                throw new ValidationException(getName(), "Unexpected exception: " + e.getMessage(), e);
            }
        }

        final String actualRootHashLine = String.format(HASH_INFO_TEMPLATE, Mnemonics.generateMnemonic(snapshotHash));

        if (!java.util.Objects.equals(expectedRootHashLine, actualRootHashLine)) {
            throw new ValidationException(
                    getName(), String.format("Expected <%s> but was <%s>", expectedRootHashLine, actualRootHashLine));
        }
    }
}

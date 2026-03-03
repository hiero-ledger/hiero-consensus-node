// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import static com.hedera.statevalidation.validator.RehashValidator.REHASH_GROUP;
import static org.hiero.consensus.platformstate.PlatformStateUtils.getInfoString;

import com.hedera.statevalidation.util.ConfigUtils;
import com.hedera.statevalidation.validator.util.ValidationAssertions;
import com.swirlds.state.merkle.VirtualMapState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Validator that validates the root hash of the state by comparing
 * the root hash line against a reference file.
 *
 * <p>This validator runs independently (not through the data pipeline) because it
 * requires reading an external hash info file for comparison.
 * @see Validator
 */
public class RootHashValidator implements Validator {

    public static final String ROOT_HASH_NAME = "rootHash";

    /**
     * The file name containing the expected hash info.
     */
    public static final String HASH_INFO_FILE_NAME = "hashInfo.txt";

    private VirtualMapState state;
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
        this.state = state;

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
        final String infoStringFromState = getInfoString(state);

        final List<String> fullList = Arrays.asList(infoStringFromState.split("\n"));
        String actualRootHashLine = "";
        for (String line : fullList) {
            if (line.contains("(root)")) {
                actualRootHashLine = line;
                break;
            }
        }

        ValidationAssertions.requireEqual(expectedRootHashLine, actualRootHashLine, getName());
    }
}

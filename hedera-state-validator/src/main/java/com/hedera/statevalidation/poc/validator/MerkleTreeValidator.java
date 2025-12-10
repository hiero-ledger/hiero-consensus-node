// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.validator;

import com.hedera.statevalidation.poc.util.ValidationAssertions;
import com.hedera.statevalidation.poc.validator.api.Validator;
import com.hedera.statevalidation.util.ConfigUtils;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.state.MerkleNodeState;
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
 * Validator that validates the Merkle tree structure of the state by comparing
 * the root hash line against a reference file.
 *
 * <p>This validator runs independently (not through the data pipeline) because it
 * requires reading an external hash info file for comparison.
 */
public class MerkleTreeValidator implements Validator {

    // TODO: revisit tag and validator name
    public static final String MERKLE_TREE_TAG = "merkleTree";

    /**
     * The file name containing the expected hash info.
     */
    public static final String HASH_INFO_FILE_NAME = "hashInfo.txt";

    /**
     * This parameter defines how deep the hash tree should be traversed.
     * Note that it doesn't go below the top level of VirtualMap even if the depth is set to a higher value.
     * TODO: check if this is needed
     */
    private static final int HASH_DEPTH = 5;

    private MerkleNodeState state;
    private String expectedRootHashLine;

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getTag() {
        return MERKLE_TREE_TAG;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(@NonNull final DeserializedSignedState deserializedSignedState) {
        //noinspection resource
        this.state = deserializedSignedState.reservedSignedState().get().getState();

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
        final PlatformStateFacade platformStateFacade = PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;
        final String infoStringFromState = platformStateFacade.getInfoString(state, HASH_DEPTH);

        final List<String> fullList = Arrays.asList(infoStringFromState.split("\n"));
        String actualRootHashLine = "";
        for (String line : fullList) {
            if (line.contains("(root)")) {
                actualRootHashLine = line;
                break;
            }
        }

        ValidationAssertions.requireEqual(expectedRootHashLine, actualRootHashLine, getTag());
    }
}

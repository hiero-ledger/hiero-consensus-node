// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators.merkledb;

import static com.hedera.statevalidation.validators.Constants.VALIDATE_FILE_LAYOUT;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.hedera.statevalidation.validators.Constants;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Validates the file layout of the state folder.
 */
@ExtendWith({StateResolver.class})
@Tag("files")
public class FileLayout {

    private static final Logger log = LogManager.getLogger(FileLayout.class);

    // Index paths

    // internalHashStoreDisk templates
    private static final String INTERNAL_HASH_METADATA_TMPL =
            ".*data.state.internalHashStoreDisk.state_internalhashes_metadata[.]pbj";
    private static final String INTERNAL_HASH_STORE_TMPL =
            ".*data.state.internalHashStoreDisk.state_internalhashes_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-\\d{3}[_]+\\d+[.]pbj";

    // objectKeyToPath templates
    private static final String OBJECT_KEY_TO_PATH_BUCKET_INDEX_TMPL =
            ".*data.state.objectKeyToPath.state_objectkeytopath_bucket_index[.]ll";
    private static final String OBJECT_KEY_TO_PATH_METADATA_TMPL =
            ".*data.state.objectKeyToPath.state_objectkeytopath_metadata[.]pbj";
    private static final String OBJECT_KEY_TO_PATH_STORE_TMPL =
            ".*data.state.objectKeyToPath.state_objectkeytopath_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-\\d{3}[_]+\\d+[.]pbj";
    private static final String OBJECT_KEY_TO_PATH_METADATA_HDHM_TMPL =
            ".*data.state.objectKeyToPath.state_objectkeytopath_metadata[.]hdhm";

    // pathToHashKeyValue templates
    private static final String PATH_TO_HASH_KEY_VALUE_METADATA_TMPL =
            ".*data.state.pathToHashKeyValue.state_pathtohashkeyvalue_metadata.*[.]pbj";
    private static final String PATH_TO_HASH_KEY_VALUE_STORE_TMPL =
            ".*data.state.pathToHashKeyValue.state_pathtohashkeyvalue_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-\\d{3}[_]+\\d+[.]pbj";

    // metadata
    private static final String METADATA_PBJ_TMPL = ".*data.state.table_metadata[.]pbj";
    private static final String PATH_TO_DISK_INTERNAL_TMPL = ".*data.state.pathToDiskLocationInternalNodes[.]ll";
    private static final String PATH_TO_DISK_LEAF_TMPL = ".*data.state.pathToDiskLocationLeafNodes[.]ll";

    // Other files
    private static final List<String> EXPECTED_FILE_PATTERNS = List.of(
            ".*state.vmap",
            ".*emergencyRecovery.yaml",
            ".*hashInfo.txt",
            ".*settingsUsed.txt",
            ".*signatureSet.bin",
            ".*SignedState.swh",
            ".*stateMetadata.txt",
            ".*VERSION");

    @Test
    public void validateFileLayout(DeserializedSignedState deserializedState) throws IOException {
        if (!VALIDATE_FILE_LAYOUT) {
            log.warn("File layout validation is disabled. Skipping file layout validation.");
            return;
        }

        List<OptionalPattern> expectedPathPatterns = new ArrayList<>(EXPECTED_FILE_PATTERNS.stream()
                .map(Pattern::compile)
                .map(v -> new OptionalPattern(v, false))
                .toList());
        expectedPathPatterns.addAll(indexPathsToMatch());

        Path statePath = Path.of(Constants.STATE_DIR);
        Files.walk(statePath).filter(Files::isRegularFile).forEach(path -> {
            Iterator<OptionalPattern> iterator = expectedPathPatterns.iterator();
            while (iterator.hasNext()) {
                Pattern pattern = iterator.next().pattern;
                if (pattern.matcher(path.toString()).matches()) {
                    try {
                        if (Files.size(path) > 0) {
                            iterator.remove();
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    break;
                }
            }
        });

        if (!expectedPathPatterns.isEmpty()) {
            var required = expectedPathPatterns.stream()
                    .filter(v -> !v.optional)
                    .map(v -> v.pattern)
                    .toList();
            var optional = expectedPathPatterns.stream()
                    .filter(v -> v.optional)
                    .map(v -> v.pattern)
                    .toList();
            if (!required.isEmpty()) {
                fail("The following required files were not found or they are empty: " + required);
            }

            if (!optional.isEmpty()) {
                log.info("The following optional files were not found: {}", optional);
            }
        }
    }

    private List<OptionalPattern> indexPathsToMatch() {
        return Stream.of(
                        INTERNAL_HASH_METADATA_TMPL,
                        INTERNAL_HASH_STORE_TMPL,
                        OBJECT_KEY_TO_PATH_BUCKET_INDEX_TMPL,
                        OBJECT_KEY_TO_PATH_METADATA_TMPL,
                        OBJECT_KEY_TO_PATH_STORE_TMPL,
                        OBJECT_KEY_TO_PATH_METADATA_HDHM_TMPL,
                        PATH_TO_HASH_KEY_VALUE_METADATA_TMPL,
                        PATH_TO_HASH_KEY_VALUE_STORE_TMPL,
                        METADATA_PBJ_TMPL,
                        PATH_TO_DISK_INTERNAL_TMPL,
                        PATH_TO_DISK_LEAF_TMPL)
                .map(Pattern::compile)
                .map(v -> new OptionalPattern(v, false))
                .toList();
    }

    record OptionalPattern(Pattern pattern, boolean optional) {}
}

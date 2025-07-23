// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.util;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.test.fixtures.io.InputOutputStream;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.io.IOException;
import java.nio.file.Path;

public class MerkleSerializeUtils {

    public static <T extends MerkleNode> T serializeDeserialize(final Path directory, final T root) throws IOException {
        try (InputOutputStream io = new InputOutputStream()) {
            final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
            io.getOutput().writeMerkleTree(directory, root);
            io.startReading();
            return io.getInput().readMerkleTree(configuration, directory, Integer.MAX_VALUE);
        }
    }
}

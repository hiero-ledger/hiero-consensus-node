// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.util;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.test.fixtures.io.InputOutputStream;
import java.io.IOException;
import java.nio.file.Path;

public class MerkleSerializeUtils {

    public static <T extends MerkleNode> T serializeDeserialize(final Path directory, final T root) throws IOException {
        try (InputOutputStream io = new InputOutputStream()) {
            io.getOutput().writeMerkleTree(directory, root);
            io.startReading();
            return io.getInput().readMerkleTree(directory, Integer.MAX_VALUE);
        }
    }
}

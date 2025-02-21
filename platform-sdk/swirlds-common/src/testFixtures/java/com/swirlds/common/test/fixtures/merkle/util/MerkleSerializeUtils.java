/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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

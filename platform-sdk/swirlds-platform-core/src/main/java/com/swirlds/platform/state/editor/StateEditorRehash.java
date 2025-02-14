/*
 * Copyright (C) 2016-2025 Hedera Hashgraph, LLC
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

package com.swirlds.platform.state.editor;

import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.merkle.utility.MerkleUtils;
import com.swirlds.platform.state.signed.ReservedSignedState;
import picocli.CommandLine;

@CommandLine.Command(
        name = "rehash",
        mixinStandardHelpOptions = true,
        description = "Recompute the hash for the state.")
@SubcommandOf(StateEditorRoot.class)
public class StateEditorRehash extends StateEditorOperation {

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        try (final ReservedSignedState reservedSignedState = getStateEditor().getState("StateEditorRehash.run()")) {
            MerkleUtils.rehashTree(reservedSignedState.get().getState());
        }
    }
}

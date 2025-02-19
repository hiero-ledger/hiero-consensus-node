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

package com.swirlds.platform.state.editor;

import static com.swirlds.common.merkle.copy.MerkleCopy.copyTreeToLocation;
import static com.swirlds.platform.state.editor.StateEditorUtils.formatNode;

import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.crypto.Hashable;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.route.MerkleRouteIterator;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.signed.ReservedSignedState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;

@CommandLine.Command(name = "swap", mixinStandardHelpOptions = true, description = "Swap two nodes.")
@SubcommandOf(StateEditorRoot.class)
public class StateEditorSwap extends StateEditorOperation {
    private static final Logger logger = LogManager.getLogger(StateEditorSwap.class);

    private String pathA;
    private String pathB = "";

    @CommandLine.Parameters(index = "0", description = "The first merkle node.")
    private void setPathA(final String pathA) {
        this.pathA = pathA;
    }

    @CommandLine.Parameters(index = "1", arity = "0..1", description = "The second merkle node.")
    private void setPathB(final String pathB) {
        this.pathB = pathB;
    }

    @Override
    public void run() {
        try (final ReservedSignedState reservedSignedState = getStateEditor().getState("StateEditorLoad.swap()")) {
            final StateEditor.ParentInfo parentInfoA = getStateEditor().getParentInfo(pathA);
            final StateEditor.ParentInfo parentInfoB = getStateEditor().getParentInfo(pathB);

            final MerkleNodeState merkleTraversable = reservedSignedState.get().getState();
            final MerkleNode nodeA = merkleTraversable.getNodeAtRoute(parentInfoA.target());
            final MerkleNode nodeB = merkleTraversable.getNodeAtRoute(parentInfoB.target());

            if (logger.isInfoEnabled(LogMarker.CLI.getMarker())) {
                logger.info(LogMarker.CLI.getMarker(), "Swapping {} and {}", formatNode(nodeA), formatNode(nodeB));
            }

            // Take a reservation on B so it doesn't get prematurely deleted
            if (nodeB != null) {
                nodeB.reserve();
            }

            copyTreeToLocation(parentInfoB.parent(), parentInfoB.indexInParent(), nodeA);
            copyTreeToLocation(parentInfoA.parent(), parentInfoA.indexInParent(), nodeB);

            // Release the artificial reservation
            if (nodeB != null) {
                nodeB.release();
            }

            // Invalidate hashes in path down from root
            new MerkleRouteIterator(merkleTraversable, parentInfoA.parent().getRoute())
                    .forEachRemaining(Hashable::invalidateHash);
            new MerkleRouteIterator(merkleTraversable, parentInfoB.parent().getRoute())
                    .forEachRemaining(Hashable::invalidateHash);
        }
    }
}

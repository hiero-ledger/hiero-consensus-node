// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.editor;

import static com.swirlds.platform.state.editor.StateEditorUtils.formatNode;
import static com.swirlds.platform.state.editor.StateEditorUtils.formatParent;

import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.merkle.route.MerkleRouteIterator;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.state.MerkleNodeState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hashable;
import picocli.CommandLine;

@CommandLine.Command(
        name = "rm",
        mixinStandardHelpOptions = true,
        description = "Remove a node, replacing it with null.")
@SubcommandOf(StateEditorRoot.class)
public class StateEditorRm extends StateEditorOperation {
    private static final Logger logger = LogManager.getLogger(StateEditorRm.class);

    private String path = "";

    @CommandLine.Parameters(arity = "0..1", description = "The route of the node to remove.")
    private void setPath(final String path) {
        this.path = path;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        final StateEditor.ParentInfo parentInfo = getStateEditor().getParentInfo(path);
        final MerkleRoute destinationRoute = parentInfo.target();
        final MerkleInternal parent = parentInfo.parent();
        final int indexInParent = parentInfo.indexInParent();

        try (final ReservedSignedState reservedSignedState = getStateEditor().getState("StateEditorRm.run()")) {
            final MerkleNodeState state = reservedSignedState.get().getState();
            final MerkleNode child = state.getRoot().getNodeAtRoute(destinationRoute);

            if (logger.isInfoEnabled(LogMarker.CLI.getMarker())) {
                logger.info(
                        LogMarker.CLI.getMarker(),
                        "Removing {} from parent {}",
                        formatNode(child),
                        formatParent(parent, indexInParent));
            }

            parent.setChild(indexInParent, null);

            // Invalidate hashes in path down from root
            new MerkleRouteIterator(state.getRoot(), parent.getRoute()).forEachRemaining(Hashable::invalidateHash);
        }
    }
}

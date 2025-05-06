// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.editor;

import static com.swirlds.platform.state.editor.StateEditorUtils.formatNode;
import static com.swirlds.platform.state.editor.StateEditorUtils.formatParent;
import static com.swirlds.platform.state.editor.StateEditorUtils.formatRoute;

import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.copy.MerkleCopy;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.merkle.route.MerkleRouteIterator;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.state.signed.ReservedSignedState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hashable;
import picocli.CommandLine;

@CommandLine.Command(
        name = "cp",
        mixinStandardHelpOptions = true,
        description = "Copy a node from one location to another.")
@SubcommandOf(StateEditorRoot.class)
public class StateEditorCp extends StateEditorOperation {
    private static final Logger logger = LogManager.getLogger(StateEditorCp.class);

    private String sourcePath;
    private String destinationPath = "";

    @CommandLine.Parameters(index = "0", description = "The route of the node to be copied.")
    private void setSourcePath(final String sourcePath) {
        this.sourcePath = sourcePath;
    }

    @CommandLine.Parameters(index = "1", arity = "0..1", description = "The route where the node should be copied to.")
    private void setDestinationPath(final String destinationPath) {
        this.destinationPath = destinationPath;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        final StateEditor.ParentInfo parentInfo = getStateEditor().getParentInfo(destinationPath);
        final MerkleRoute destinationRoute = parentInfo.target();
        final MerkleInternal parent = parentInfo.parent();
        final int indexInParent = parentInfo.indexInParent();

        final MerkleNode source = getStateEditor().getRelativeNode(sourcePath);

        if (logger.isInfoEnabled(LogMarker.CLI.getMarker())) {
            logger.info(
                    LogMarker.CLI.getMarker(),
                    "Copying {} to {} in parent {}",
                    formatNode(source),
                    formatRoute(destinationRoute),
                    formatParent(parent, indexInParent));
        }

        MerkleCopy.copyTreeToLocation(parent.asInternal(), indexInParent, source);

        // Invalidate hashes in path down from root
        try (final ReservedSignedState reservedSignedState = getStateEditor().getState("StateEditorCp.run()")) {
            new MerkleRouteIterator(reservedSignedState.get().getState().getRoot(), parent.getRoute())
                    .forEachRemaining(Hashable::invalidateHash);
        }
    }
}

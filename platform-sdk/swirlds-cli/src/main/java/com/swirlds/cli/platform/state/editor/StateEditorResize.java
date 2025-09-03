// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.platform.state.editor;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.route.MerkleRouteUtils;
import com.swirlds.logging.legacy.LogMarker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;

@CommandLine.Command(
        name = "resize",
        mixinStandardHelpOptions = true,
        description = "Change the number of children in an internal node.")
// @SubcommandOf(StateEditorRoot.class) // This can be un-commented when resizing is implemented
public class StateEditorResize extends StateEditorOperation {

    private static final Logger logger = LogManager.getLogger(StateEditorResize.class);

    private String parentPath = "";
    private int newSize;

    @CommandLine.Parameters(index = "0", description = "The new size of the parent.")
    private void setNewSize(final int newSize) {
        this.newSize = newSize;
    }

    @CommandLine.Parameters(index = "1", arity = "0..1", description = "The route of the parent will be resized.")
    private void setParentPath(final String parentPath) {
        this.parentPath = parentPath;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        final MerkleNode parent = getStateEditor().getRelativeNode(parentPath);
        if (parent == null) {
            throw new IllegalArgumentException("The node at " + parentPath + " is null.");
        }
        if (!(parent instanceof MerkleInternal)) {
            throw new IllegalArgumentException("The node at " + parentPath + " is of type "
                    + parent.getClass().getSimpleName() + " and is not an internal node.");
        }

        if (logger.isInfoEnabled(LogMarker.CLI.getMarker())) {
            logger.info(
                    LogMarker.CLI.getMarker(),
                    "Resizing [{} {}] to child count {}",
                    MerkleRouteUtils.merkleRouteToPathFormat(parent.getRoute()),
                    parent.getClass().getSimpleName(),
                    newSize);
        }

        // FUTURE WORK implement merkle resizing... this would be a really handy feature!
    }
}

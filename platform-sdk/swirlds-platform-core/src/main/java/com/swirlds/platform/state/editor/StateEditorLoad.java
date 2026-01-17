// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.editor;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.platform.state.editor.StateEditorUtils.formatFile;
import static com.swirlds.platform.state.editor.StateEditorUtils.formatNodeType;
import static com.swirlds.platform.state.editor.StateEditorUtils.formatParent;
import static com.swirlds.platform.state.editor.StateEditorUtils.formatRoute;

import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.merkle.route.MerkleRouteIterator;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.state.signed.ReservedSignedState;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hashable;
import picocli.CommandLine;

@CommandLine.Command(
        name = "load",
        mixinStandardHelpOptions = true,
        description = "load a subtree from file and put it into the state")
@SubcommandOf(StateEditorRoot.class)
public class StateEditorLoad extends StateEditorOperation {
    private static final Logger logger = LogManager.getLogger(StateEditorLoad.class);

    private String destinationPath = "";
    private Path fileName;

    @CommandLine.Parameters(index = "0", description = "The location on disk where the subtree should be loaded from.")
    private void setFileName(final Path fileName) {
        this.fileName = pathMustExist(getAbsolutePath(fileName));
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

        if (logger.isInfoEnabled(LogMarker.CLI.getMarker())) {
            logger.info(LogMarker.CLI.getMarker(), "Loading subtree from {}", formatFile(fileName));
        }

        final MerkleNode subtree;
        try (final MerkleDataInputStream in =
                new MerkleDataInputStream(new BufferedInputStream(new FileInputStream(fileName.toFile())))) {
            subtree = in.readMerkleTree(fileName.getParent(), Integer.MAX_VALUE);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }

        if (logger.isInfoEnabled(LogMarker.CLI.getMarker())) {
            logger.info(
                    LogMarker.CLI.getMarker(),
                    "Loaded {} subtree into {}, new parent is {}",
                    formatNodeType(subtree),
                    formatRoute(destinationRoute),
                    formatParent(parent, indexInParent));
        }

        parent.asInternal().setChild(indexInParent, subtree);

        // Invalidate hashes in path down from root
        try (final ReservedSignedState reservedSignedState = getStateEditor().getState("StateEditorLoad.run()")) {
            new MerkleRouteIterator(reservedSignedState.get().getState().getRoot(), parent.getRoute())
                    .forEachRemaining(Hashable::invalidateHash);
        }
    }
}

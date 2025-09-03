// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.platform.state.editor;

import static com.swirlds.cli.platform.state.editor.StateEditorUtils.formatFile;
import static com.swirlds.cli.platform.state.editor.StateEditorUtils.formatNode;
import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;

import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.logging.legacy.LogMarker;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;

@CommandLine.Command(name = "store", mixinStandardHelpOptions = true, description = "Store a subtree in a file.")
@SubcommandOf(StateEditorRoot.class)
public class StateEditorStore extends StateEditorOperation {
    private static final Logger logger = LogManager.getLogger(StateEditorStore.class);

    private String path = "";
    private Path fileName;

    @CommandLine.Parameters(arity = "0..1", description = "The target route.")
    private void setPath(final String path) {
        this.path = path;
    }

    @CommandLine.Parameters(description = "The location on disk where the subtree should be stored.")
    private void setFileName(final Path fileName) {
        this.fileName = pathMustNotExist(getAbsolutePath(fileName));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        try {
            final MerkleNode subtree = getStateEditor().getRelativeNode(path);

            if (!Files.exists(fileName.getParent())) {
                Files.createDirectories(fileName.getParent());
            }

            if (logger.isInfoEnabled(LogMarker.CLI.getMarker())) {
                logger.info(LogMarker.CLI.getMarker(), "Writing {} to {}", formatNode(subtree), formatFile(fileName));
            }

            final MerkleDataOutputStream out =
                    new MerkleDataOutputStream(new BufferedOutputStream(new FileOutputStream(fileName.toFile())));

            out.writeMerkleTree(fileName.getParent(), subtree);
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

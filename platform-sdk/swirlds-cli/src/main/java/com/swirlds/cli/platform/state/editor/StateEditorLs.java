// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.platform.state.editor;

import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.utility.MerkleTreeVisualizer;
import picocli.CommandLine;

@CommandLine.Command(
        name = "ls",
        mixinStandardHelpOptions = true,
        description = "Print information about nodes at this position in the state.")
@SubcommandOf(StateEditorRoot.class)
public class StateEditorLs extends StateEditorOperation {

    private String path = "";
    private int depth = 10;
    private boolean verbose = false;

    @CommandLine.Parameters(arity = "0..1", description = "The route to show.")
    private void setPath(final String path) {
        this.path = path;
    }

    @CommandLine.Option(
            names = {"-d", "--depth"},
            description = "The maximum depth to show, relative to the current working route.")
    private void setDepth(final int depth) {
        this.depth = depth;
    }

    @CommandLine.Option(
            names = {"-v", "--verbose"},
            description = "Enable verbose mode, where we don't ignore nodes inside the map classes.")
    private void setVerbose(final boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("java:S106")
    @Override
    public void run() {
        final MerkleNode node = getStateEditor().getRelativeNode(path);
        final MerkleTreeVisualizer visualizer = new MerkleTreeVisualizer(node)
                .setDepth(depth)
                .setIgnoreDepthAnnotations(verbose)
                .setHashLength(8)
                .setUseColors(true);
        System.out.println("\n" + visualizer);
    }
}

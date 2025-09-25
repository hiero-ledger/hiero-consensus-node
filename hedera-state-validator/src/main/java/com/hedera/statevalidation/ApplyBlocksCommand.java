// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import static com.hedera.statevalidation.blockstream.BlockStreamRecoveryWorkflow.applyBlocks;

import com.swirlds.cli.utility.AbstractCommand;
import java.nio.file.Path;
import org.hiero.consensus.model.node.NodeId;
import picocli.CommandLine;

/**
 * A command applying a set of blocks to the state
 */
@CommandLine.Command(name = "apply-blocks", description = "Update the state by applying blocks from a block stream.")
public class ApplyBlocksCommand extends AbstractCommand {

    @CommandLine.ParentCommand
    private StateOperatorCommand parent;

    public static final long DEFAULT_TARGET_ROUND = Long.MAX_VALUE;
    private Path outputPath = Path.of("./out");
    private NodeId selfId;
    private long targetRound = DEFAULT_TARGET_ROUND;
    private Path blockStreamDirectory;
    private String expectedHash = "";

    private ApplyBlocksCommand() {}

    @CommandLine.Option(
            names = {"-o", "--out"},
            description =
                    "The location where output is written. Default = './out'. " + "Must not exist prior to invocation.")
    private void setOutputPath(final Path outputPath) {
        this.outputPath = outputPath;
    }

    @CommandLine.Parameters(index = "0", description = "The path to a directory tree containing block stream files.")
    private void setBlockStreamDirectory(final Path blockStreamDirectory) {
        this.blockStreamDirectory = pathMustExist(blockStreamDirectory.toAbsolutePath());
    }

    @CommandLine.Option(
            names = {"-i", "--id"},
            required = true,
            description = "The ID of the node that is being used to recover the state. "
                    + "This node's keys should be available locally.")
    private void setSelfId(final long selfId) {
        this.selfId = NodeId.of(selfId);
    }

    @CommandLine.Option(
            names = {"-t", "--target-round"},
            defaultValue = "9223372036854775807",
            description = "The last round that should be applied to the state, any higher rounds are ignored. "
                    + "Default = apply all available rounds")
    private void setTargetRound(final long targetRound) {
        this.targetRound = targetRound;
    }

    @CommandLine.Option(
            names = {"-h", "--expected-hash"},
            defaultValue = "",
            description = "Expected hash of the resulting state")
    private void setExpectedHash(final String expectedHash) {
        this.expectedHash = expectedHash;
    }

    @Override
    public Integer call() throws Exception {
        System.setProperty("state.dir", parent.getStateDir().getAbsolutePath());

        applyBlocks(blockStreamDirectory, selfId, targetRound, outputPath, expectedHash);
        return 0;
    }
}

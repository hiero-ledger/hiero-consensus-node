// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli;

import static com.swirlds.platform.blockstream.BlockStreamRecoveryWorkflow.applyBlocks;

import com.swirlds.cli.commands.BlockStreamCommand;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import java.nio.file.Path;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;
import picocli.CommandLine;

/**
 * A command applying a set of blocks to the state
 */
@CommandLine.Command(
        name = "apply",
        mixinStandardHelpOptions = true,
        description = "Build a state file by applying blocks from a block stream.")
@SubcommandOf(BlockStreamCommand.class)
public final class BlockStreamApplyCommand extends AbstractCommand {

    public static final long DEFAULT_TARGET_ROUND = Long.MAX_VALUE;
    private Path outputPath = Path.of("./out");
    private String appMainName;
    private Path pathToInitState;
    private NodeId selfId;
    private long targetRound = DEFAULT_TARGET_ROUND;
    private Path blockStreamDirectory;
    private List<Path> configurationPaths = List.of();
    private String expectedHash = "";

    private BlockStreamApplyCommand() {}

    @CommandLine.Option(
            names = {"-c", "--config"},
            description = "A path to where a configuration file can be found. If not provided then defaults are used.")
    private void setConfigurationPath(final List<Path> configurationPaths) {
        configurationPaths.forEach(this::pathMustExist);
        this.configurationPaths = configurationPaths;
    }

    @CommandLine.Option(
            names = {"-o", "--out"},
            description =
                    "The location where output is written. Default = './out'. " + "Must not exist prior to invocation.")
    private void setOutputPath(final Path outputPath) {
        this.outputPath = outputPath;
    }

    @CommandLine.Parameters(index = "1", description = "The path to a directory tree containing event stream files.")
    private void setBlockStreamDirectory(final Path eventStreamDirectory) {
        this.blockStreamDirectory = pathMustExist(eventStreamDirectory.toAbsolutePath());
    }

    @CommandLine.Option(
            names = {"-n", "--main-name"},
            required = true,
            description = "The fully qualified name of the application's main class.")
    private void setAppMainName(final String appMainName) {
        this.appMainName = appMainName;
    }

    @CommandLine.Parameters(
            index = "0",
            description = "The path to the bootstrap SignedState.swh file."
                    + "Events will be replayed on top of this state file.")
    private void setPathToInitState(final Path pathToInitState) {
        this.pathToInitState = pathMustExist(pathToInitState.toAbsolutePath());
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
            description = "The last round that should be applied to the state, any higher rounds are ignored. "
                    + "Default = apply all available rounds")
    private void setExpectedHash(final String expectedHash) {
        this.expectedHash = expectedHash;
    }

    @Override
    public Integer call() throws Exception {
        applyBlocks(
                pathToInitState,
                configurationPaths,
                blockStreamDirectory,
                appMainName,
                selfId,
                targetRound,
                outputPath,
                expectedHash);
        return 0;
    }
}

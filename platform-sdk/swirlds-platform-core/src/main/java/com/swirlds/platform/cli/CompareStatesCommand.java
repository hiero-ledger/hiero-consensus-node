// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;

import com.swirlds.cli.commands.StateCommand;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.config.DefaultConfiguration;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedStateComparison;
import com.swirlds.platform.state.snapshot.SignedStateFileReader;
import com.swirlds.platform.util.BootstrapUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "compare",
        mixinStandardHelpOptions = true,
        description = "Compare two signed states for differences. Useful for debugging ISS incidents.")
@SubcommandOf(StateCommand.class)
public final class CompareStatesCommand extends AbstractCommand {
    private static final Logger logger = LogManager.getLogger(CompareStatesCommand.class);

    /**
     * The path to the first state being compared.
     */
    private Path stateAPath;

    /**
     * The path to the first state being compared.
     */
    private Path stateBPath;

    /**
     * The maximum number of nodes to print.
     */
    private int nodeLimit = 100;

    /**
     * If true then do a deep comparison of the states.
     */
    private boolean deepComparison = false;

    /**
     * Load configuration from these files.
     */
    private List<Path> configurationPaths = List.of();

    private CompareStatesCommand() {}

    /**
     * Set the configuration paths.
     */
    @CommandLine.Option(
            names = {"-c", "--config"},
            description = "A path to where a configuration file can be found. If not provided then defaults are used.")
    private void setConfigurationPath(final List<Path> configurationPaths) {
        configurationPaths.forEach(this::pathMustExist);
        this.configurationPaths = configurationPaths;
    }

    /**
     * Set the path to state A.
     */
    @CommandLine.Parameters(description = "the path to the first SignedState.swh that is being compared")
    private void setStateAPath(final Path stateAPath) {
        this.stateAPath = pathMustExist(stateAPath.toAbsolutePath());
    }

    /**
     * Set the path to state B.
     */
    @CommandLine.Parameters(description = "the path to the second SignedState.swh that is being compared")
    private void setStateBPath(final Path stateBPath) {
        this.stateBPath = pathMustExist(stateBPath.toAbsolutePath());
    }

    @CommandLine.Option(
            names = {"--limit"},
            description = "the maximum number of mismatched merkle nodes to print")
    private void setNodeLimit(final int nodeLimit) {
        if (nodeLimit <= 0) {
            throw new CommandLine.ParameterException(getSpec().commandLine(), "node limit must be non-zero positive");
        }
        this.nodeLimit = nodeLimit;
    }

    @CommandLine.Option(
            names = {"--deep"},
            description = "if set then do a deep comparison of the states, "
                    + "useful if internal hashes have been corrupted")
    private void setDeepComparison(final boolean deepComparison) {
        this.deepComparison = deepComparison;
    }

    /**
     * Load a state from disk and hash it.
     *
     * @param statePath the location of the state to load
     * @return the loaded state
     */
    private static ReservedSignedState loadAndHashState(
            @NonNull final PlatformContext platformContext, @NonNull final Path statePath) throws IOException {
        Objects.requireNonNull(platformContext);
        Objects.requireNonNull(statePath);

        logger.info(LogMarker.CLI.getMarker(), "Loading state from {}", statePath);

        final ReservedSignedState signedState = SignedStateFileReader.readStateFile(
                        statePath,
                        (virtualMap) -> {
                            // FUTURE WORK: https://github.com/hiero-ledger/hiero-consensus-node/issues/19003
                            throw new UnsupportedOperationException();
                        },
                        DEFAULT_PLATFORM_STATE_FACADE,
                        platformContext)
                .reservedSignedState();
        logger.info(LogMarker.CLI.getMarker(), "Hashing state");
        try {
            platformContext
                    .getMerkleCryptography()
                    .digestTreeAsync(signedState.get().getState().getRoot())
                    .get();
        } catch (final InterruptedException | ExecutionException e) {
            throw new RuntimeException("unable to hash state", e);
        }

        return signedState;
    }

    /**
     * This method is called after command line input is parsed.
     *
     * @return return code of the program
     */
    @Override
    public Integer call() throws IOException {
        BootstrapUtils.setupConstructableRegistry();

        final Configuration configuration = DefaultConfiguration.buildBasicConfiguration(
                ConfigurationBuilder.create(), getAbsolutePath("settings.txt"), configurationPaths);
        final PlatformContext platformContext = PlatformContext.create(configuration);

        try (final ReservedSignedState stateA = loadAndHashState(platformContext, stateAPath)) {
            try (final ReservedSignedState stateB = loadAndHashState(platformContext, stateBPath)) {
                SignedStateComparison.printMismatchedNodes(
                        SignedStateComparison.mismatchedNodeIterator(
                                stateA.get().getState().getRoot(),
                                stateB.get().getState().getRoot(),
                                deepComparison),
                        nodeLimit);
            }
        }

        return 0;
    }
}

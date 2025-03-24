// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.platform.recovery.EventRecoveryWorkflow.recoverState;
import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;

import com.swirlds.cli.commands.EventStreamCommand;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.config.DefaultConfiguration;
import java.nio.file.Path;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;
import picocli.CommandLine;

@CommandLine.Command(
        name = "recover",
        mixinStandardHelpOptions = true,
        description = "Build a state file by replaying events from an event stream.")
@SubcommandOf(EventStreamCommand.class)
public final class EventStreamRecoverCommand extends AbstractCommand {

    private Path outputPath = Path.of("./out");
    private String appMainName;
    private Path bootstrapSignedState;
    private NodeId selfId;
    private boolean ignorePartialRounds;
    private long finalRound = -1;
    private Path eventStreamDirectory;
    private List<Path> configurationPaths = List.of();
    private boolean loadSigningKeys;

    private EventStreamRecoverCommand() {}

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
    private void setEventStreamDirectory(final Path eventStreamDirectory) {
        this.eventStreamDirectory = pathMustExist(eventStreamDirectory.toAbsolutePath());
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
    private void setBootstrapSignedState(final Path bootstrapSignedState) {
        this.bootstrapSignedState = pathMustExist(bootstrapSignedState.toAbsolutePath());
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
            names = {"-p", "--ignore-partial"},
            description = "if set then any partial rounds at the end of the event stream are ignored. Default = false")
    private void setIgnorePartialRounds(final boolean ignorePartialRounds) {
        this.ignorePartialRounds = ignorePartialRounds;
    }

    @CommandLine.Option(
            names = {"-f", "--final-round"},
            defaultValue = "-1",
            description = "The last round that should be applied to the state, any higher rounds are ignored. "
                    + "Default = apply all available rounds")
    private void setFinalRound(final long finalRound) {
        this.finalRound = finalRound;
    }

    @CommandLine.Option(
            names = {"-s", "--load-signing-keys"},
            defaultValue = "false",
            description = "If present then load the signing keys. If not present, calling platform.sign() will throw.")
    private void setLoadSigningKeys(final boolean loadSigningKeys) {
        this.loadSigningKeys = loadSigningKeys;
    }

    @Override
    public Integer call() throws Exception {
        final Configuration configuration = DefaultConfiguration.buildBasicConfiguration(
                ConfigurationBuilder.create(), getAbsolutePath("settings.txt"), configurationPaths);
        final PlatformContext platformContext = PlatformContext.create(configuration);

        recoverState(
                platformContext,
                bootstrapSignedState,
                configurationPaths,
                eventStreamDirectory,
                appMainName,
                !ignorePartialRounds,
                finalRound,
                outputPath,
                selfId,
                loadSigningKeys,
                DEFAULT_PLATFORM_STATE_FACADE);
        return 0;
    }
}

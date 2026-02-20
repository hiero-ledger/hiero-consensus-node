// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.recovery;

import static org.hiero.consensus.pcli.recovery.EventRecoveryWorkflow.recoverState;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.config.DefaultConfiguration;
import java.nio.file.Path;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.pcli.AbstractCommand;
import org.hiero.consensus.pcli.EventStreamCommand;
import org.hiero.consensus.pcli.SubcommandOf;
import picocli.CommandLine;

@CommandLine.Command(
        name = "recover",
        mixinStandardHelpOptions = true,
        description = "Build a state file by replaying events from an event stream.")
@SubcommandOf(EventStreamCommand.class)
public final class EventStreamRecoverCommand extends AbstractCommand {

    private Path outputPath = Path.of("./out");
    private Path bootstrapSignedState;
    private NodeId selfId;
    private boolean ignorePartialRounds;
    private long finalRound = -1;
    private Path eventStreamDirectory;
    private Path configurationPath;
    private boolean loadSigningKeys;

    private EventStreamRecoverCommand() {}

    @CommandLine.Option(
            names = {"-c", "--config"},
            required = false,
            description = "A path to where a configuration file can be found. If not provided then defaults are used.")
    private void setConfigurationPath(final Path configurationPath) {
        pathMustExist(configurationPath);
        this.configurationPath = configurationPath;
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

    @CommandLine.Parameters(
            index = "0",
            description =
                    "The path to the bootstrap state directory." + "Events will be replayed on top of this state.")
    private void setBootstrapSignedState(final Path bootstrapSignedStateDir) {
        this.bootstrapSignedState = dirMustExist(bootstrapSignedStateDir.toAbsolutePath());
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
        final Configuration configuration =
                DefaultConfiguration.buildBasicConfiguration(ConfigurationBuilder.create(), configurationPath);

        final PlatformContext platformContext = PlatformContext.create(configuration);

        recoverState(
                platformContext,
                bootstrapSignedState,
                eventStreamDirectory,
                !ignorePartialRounds,
                finalRound,
                outputPath,
                selfId,
                loadSigningKeys);
        return 0;
    }
}

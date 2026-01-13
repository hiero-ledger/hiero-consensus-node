// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.cli.commands.StateCommand;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.config.DefaultConfiguration;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.service.ReadablePlatformStateStore;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.platform.state.snapshot.SignedStateFileReader;
import com.swirlds.platform.util.BootstrapUtils;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.StateLifecycleManagerImpl;
import com.swirlds.state.merkle.VirtualMapState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import picocli.CommandLine;

@CommandLine.Command(
        name = "extract-consensus-snapshot",
        mixinStandardHelpOptions = true,
        description = "Extract the consensus snapshot from an existing state and write it to a new json file.")
@SubcommandOf(StateCommand.class)
public class ExtractConsensusSnapshotStateCommand extends AbstractCommand {
    private Path statePath;
    private Path outputDir;

    /**
     * The path to state to edit
     */
    @CommandLine.Parameters(description = "The path to the directory of the state to read", index = "0")
    private void setStatePath(@NonNull final Path statePath) {
        this.statePath = dirMustExist(statePath.toAbsolutePath());
    }

    /**
     * The path to the output directory
     */
    @CommandLine.Parameters(description = "The path to the output directory", index = "1")
    private void setOutputDir(@NonNull final Path outputDir) {
        this.outputDir = dirMustExist(outputDir.toAbsolutePath());
    }

    @Override
    public Integer call() throws IOException, ExecutionException, InterruptedException {
        final Configuration configuration = DefaultConfiguration.buildBasicConfiguration(ConfigurationBuilder.create());
        BootstrapUtils.setupConstructableRegistry();

        final PlatformContext platformContext = PlatformContext.create(configuration);
        final StateLifecycleManager stateLifecycleManager = new StateLifecycleManagerImpl(
                platformContext.getMetrics(),
                platformContext.getTime(),
                (virtualMap) -> new VirtualMapState(virtualMap, platformContext.getMetrics()));

        System.out.printf("Reading from %s %n", statePath.toAbsolutePath());
        final DeserializedSignedState deserializedSignedState =
                SignedStateFileReader.readState(statePath, platformContext,
                        virtualMap -> new VirtualMapState(virtualMap, platformContext.getMetrics()));

        try (final ReservedSignedState reservedSignedState = deserializedSignedState.reservedSignedState()) {
            final State state = reservedSignedState.get().getState();
            final ReadablePlatformStateStore readablePlatformStateStore = new ReadablePlatformStateStore(
                    state.getReadableStates(PlatformStateService.NAME));
            final ConsensusSnapshot snapshot = readablePlatformStateStore.getSnapshot();
            if (snapshot == null) {
                System.out.printf("No snapshot found for state %s%n", statePath.toAbsolutePath());
                return 1;
            }
            final Path outputFile = outputDir.resolve("consensusSnapshot.json");
            try (final BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile.toFile()))) {
                writer.write(ConsensusSnapshot.JSON.toJSON(snapshot));
            }
            System.out.println("Consensus snapshot written to " + outputFile.toAbsolutePath());
        }

        return 0;
    }
}

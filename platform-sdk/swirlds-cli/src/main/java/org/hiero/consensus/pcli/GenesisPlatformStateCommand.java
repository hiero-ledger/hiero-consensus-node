// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli;

import static org.hiero.consensus.platformstate.PlatformStateUtils.bulkUpdateOf;
import static org.hiero.consensus.state.SignedStateFileWriter.writeSignedStateFilesToDirectory;
import static org.hiero.consensus.state.saved.SavedStateMetadata.NO_NODE_ID;

import com.hedera.pbj.runtime.ParseException;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.config.DefaultConfiguration;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateLifecycleManager;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.virtualmap.VirtualMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.PathsConfig;
import org.hiero.consensus.constructable.ConstructableRegistration;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.hashgraph.GenesisSnapshotFactory;
import org.hiero.consensus.platformstate.PlatformStateAccessor;
import org.hiero.consensus.roster.RosterStateId;
import org.hiero.consensus.roster.WritableRosterStore;
import org.hiero.consensus.state.SignedStateFileReader;
import org.hiero.consensus.state.saved.DeserializedSignedState;
import org.hiero.consensus.state.signed.ReservedSignedState;
import picocli.CommandLine;

@CommandLine.Command(
        name = "genesis",
        mixinStandardHelpOptions = true,
        description = "Edit an existing state by replacing the platform state with a new genesis state.")
@SubcommandOf(StateCommand.class)
public class GenesisPlatformStateCommand extends AbstractCommand {
    private Path statePath;
    private Path outputDir;

    /**
     * The path to state to edit
     */
    @CommandLine.Parameters(description = "The path to the directory of the state to edit", index = "0")
    private void setStatePath(final Path statePath) {
        this.statePath = dirMustExist(statePath.toAbsolutePath());
    }

    /**
     * The path to the output directory
     */
    @CommandLine.Parameters(description = "The path to the output directory", index = "1")
    private void setOutputDir(final Path outputDir) {
        this.outputDir = dirMustExist(outputDir.toAbsolutePath());
    }

    @Override
    public Integer call() throws IOException, ExecutionException, InterruptedException, ParseException {
        final Configuration configuration = DefaultConfiguration.buildBasicConfiguration(ConfigurationBuilder.create());
        ConstructableRegistration.setupConstructableRegistry();
        final PathsConfig pathsConfig = configuration.getConfigData(PathsConfig.class);
        final FileSystemManager fileSystemManager =
                new FileSystemManager(pathsConfig.savedStateDir(), pathsConfig.tmpDir());
        final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager = new VirtualMapStateLifecycleManager(
                new NoOpMetrics(),
                Time.getCurrent(),
                configuration,
                fileSystemManager);

        System.out.printf("Reading from %s %n", statePath.toAbsolutePath());
        final DeserializedSignedState deserializedSignedState =
                SignedStateFileReader.readState(statePath, configuration, stateLifecycleManager);
        final ReservedSignedState reservedSignedState = deserializedSignedState.reservedSignedState();
        bulkUpdateOf(reservedSignedState.get().getState(), v -> {
            System.out.printf("Replacing platform data %n");
            v.setRound(PlatformStateAccessor.GENESIS_ROUND);
            v.setSnapshot(GenesisSnapshotFactory.newGenesisSnapshot());
        });
        System.out.printf("Resetting the RosterService state %n");
        final State state = reservedSignedState.get().getState();
        final WritableStates writableStates = state.getWritableStates(RosterStateId.SERVICE_NAME);
        final WritableRosterStore writableRosterStore = new WritableRosterStore(writableStates);
        writableRosterStore.resetRosters();
        ((CommittableWritableStates) writableStates).commit();
        System.out.printf("Hashing state %n");
        reservedSignedState.get().getState().getHash(); // calculate hash
        System.out.printf("Writing modified state to %s %n", outputDir.toAbsolutePath());
        writeSignedStateFilesToDirectory(
                configuration,
                fileSystemManager,
                NO_NODE_ID,
                outputDir,
                reservedSignedState,
                stateLifecycleManager);

        return 0;
    }
}

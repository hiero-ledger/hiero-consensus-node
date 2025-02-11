/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.cli;

import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;
import static com.swirlds.platform.state.snapshot.SavedStateMetadata.NO_NODE_ID;
import static com.swirlds.platform.state.snapshot.SignedStateFileWriter.writeSignedStateFilesToDirectory;

import com.swirlds.cli.commands.StateCommand;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.RosterStateId;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.crypto.MerkleCryptoFactory;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.config.DefaultConfiguration;
import com.swirlds.platform.consensus.SyntheticSnapshot;
import com.swirlds.platform.state.PlatformStateAccessor;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.service.WritableRosterStore;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.platform.state.snapshot.SignedStateFileReader;
import com.swirlds.platform.util.BootstrapUtils;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.WritableStates;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
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
    @CommandLine.Parameters(description = "The path to the state to edit", index = "0")
    private void setStatePath(final Path statePath) {
        this.statePath = pathMustExist(statePath.toAbsolutePath());
    }

    /**
     * The path to the output directory
     */
    @CommandLine.Parameters(description = "The path to the output directory", index = "1")
    private void setOutputDir(final Path outputDir) {
        this.outputDir = dirMustExist(outputDir.toAbsolutePath());
    }

    @Override
    public Integer call() throws IOException, ExecutionException, InterruptedException {
        final Configuration configuration = DefaultConfiguration.buildBasicConfiguration(ConfigurationBuilder.create());
        BootstrapUtils.setupConstructableRegistry();

        final PlatformContext platformContext = PlatformContext.create(configuration);

        System.out.printf("Reading from %s %n", statePath.toAbsolutePath());
        final PlatformStateFacade stateFacade = DEFAULT_PLATFORM_STATE_FACADE;
        final DeserializedSignedState deserializedSignedState =
                SignedStateFileReader.readStateFile(configuration, statePath, stateFacade);
        try (final ReservedSignedState reservedSignedState = deserializedSignedState.reservedSignedState()) {
            stateFacade.bulkUpdateOf(reservedSignedState.get().getState(), v -> {
                System.out.printf("Replacing platform data %n");
                v.setRound(PlatformStateAccessor.GENESIS_ROUND);
                v.setSnapshot(SyntheticSnapshot.getGenesisSnapshot());

                // FUTURE WORK: remove once the AddressBook setters are deprecated and the fields are nullified.
                // For now, we have to keep these calls to ensure RosterRetriever won't fall back to using these values.
                System.out.printf("Nullifying Address Books %n");
                v.setAddressBook(null);
                v.setPreviousAddressBook(null);
            });
            {
                System.out.printf("Resetting the RosterService state %n");
                final State state = reservedSignedState.get().getState();
                final WritableStates writableStates = state.getWritableStates(RosterStateId.NAME);
                final WritableRosterStore writableRosterStore = new WritableRosterStore(writableStates);
                writableRosterStore.resetRosters();
                ((CommittableWritableStates) writableStates).commit();
            }
            System.out.printf("Hashing state %n");
            MerkleCryptoFactory.getInstance()
                    .digestTreeAsync(reservedSignedState.get().getState())
                    .get();
            System.out.printf("Writing modified state to %s %n", outputDir.toAbsolutePath());
            writeSignedStateFilesToDirectory(
                    platformContext, NO_NODE_ID, outputDir, reservedSignedState.get(), stateFacade);
        }

        return 0;
    }
}

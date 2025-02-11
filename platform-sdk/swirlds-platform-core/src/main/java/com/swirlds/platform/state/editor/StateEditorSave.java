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

package com.swirlds.platform.state.editor;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.platform.state.editor.StateEditorUtils.formatFile;
import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;
import static com.swirlds.platform.state.snapshot.SavedStateMetadata.NO_NODE_ID;
import static com.swirlds.platform.state.snapshot.SignedStateFileWriter.writeSignedStateFilesToDirectory;

import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.crypto.MerkleCryptoFactory;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.config.DefaultConfiguration;
import com.swirlds.platform.state.signed.ReservedSignedState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;

@CommandLine.Command(name = "save", mixinStandardHelpOptions = true, description = "Write the entire state to disk.")
@SubcommandOf(StateEditorRoot.class)
public class StateEditorSave extends StateEditorOperation {
    private static final Logger logger = LogManager.getLogger(StateEditorSave.class);

    private Path directory;

    @CommandLine.Parameters(description = "The directory where the saved state should be written.")
    private void setFileName(final Path directory) {
        this.directory = pathMustNotExist(getAbsolutePath(directory));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        try (final ReservedSignedState reservedSignedState = getStateEditor().getState("StateEditorSave.run()")) {

            logger.info(LogMarker.CLI.getMarker(), "Hashing state");
            MerkleCryptoFactory.getInstance()
                    .digestTreeAsync(reservedSignedState.get().getState())
                    .get();

            if (logger.isInfoEnabled(LogMarker.CLI.getMarker())) {
                logger.info(LogMarker.CLI.getMarker(), "Writing signed state file to {}", formatFile(directory));
            }

            if (!Files.exists(directory)) {
                Files.createDirectories(directory);
            }

            final Configuration configuration =
                    DefaultConfiguration.buildBasicConfiguration(ConfigurationBuilder.create());

            final PlatformContext platformContext = PlatformContext.create(configuration);

            try (final ReservedSignedState signedState = getStateEditor().getSignedStateCopy()) {
                writeSignedStateFilesToDirectory(
                        platformContext, NO_NODE_ID, directory, signedState.get(), DEFAULT_PLATFORM_STATE_FACADE);
            }

        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}

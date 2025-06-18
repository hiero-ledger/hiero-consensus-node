// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli;

import com.swirlds.base.time.Time;
import com.swirlds.cli.commands.StateCommand;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.SimpleRecycleBin;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.state.SavedStateUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Scanner;
import picocli.CommandLine;

@CommandLine.Command(
        name = "transplant",
        mixinStandardHelpOptions = true,
        description = "Prepare a state for transplanting to another network")
@SubcommandOf(StateCommand.class)
public class PrepareForTransplantCommand extends AbstractCommand {
    /** The return code for a successful operation. */
    private static final int RETURN_CODE_SUCCESS = 0;
    /** The return code when the user does not confirm the prompt */
    private static final int RETURN_CODE_PROMPT_NO = 1;

    /** The path to the state to prepare for transplant. */
    private Path statePath;

    @CommandLine.Option(
            names = {"-ac", "--auto-confirm"},
            description = "Automatically confirm the operation without prompting")
    @SuppressWarnings("unused") // used by picocli
    private boolean autoConfirm;

    /**
     * Set the path to state to prepare for transplant.
     */
    @CommandLine.Parameters(description = "The path to the state to prepare for transplant")
    @SuppressWarnings("unused") // used by picocli
    private void setStatePath(final Path statePath) {
        this.statePath = pathMustExist(statePath.toAbsolutePath());
    }

    /**
     * This method is called after command line input is parsed.
     *
     * @return return code of the program
     */
    @Override
    public Integer call() throws IOException {
        if (!autoConfirm) {
            System.out.println(
                    "Warning: This will overwrite the contents of the state directory, this is not reversible.");
            System.out.println("Do you want to continue? (Y/N): ");

            final String response;
            try (final Scanner reader = new Scanner(System.in)) {
                response = reader.nextLine();
            }
            if (!response.toUpperCase().startsWith("Y")) {
                System.out.println("Operation aborted.");
            }
            return RETURN_CODE_PROMPT_NO;
        }

        final Configuration configuration =
                ConfigurationBuilder.create().autoDiscoverExtensions().build();

        final PlatformContext platformContext = PlatformContext.create(
                configuration,
                Time.getCurrent(),
                new NoOpMetrics(),
                FileSystemManager.create(configuration),
                new SimpleRecycleBin(),
                MerkleCryptographyFactory.create(configuration));

        System.out.println("Transplanting state from: " + statePath);
        final int discardedEventCount = SavedStateUtils.prepareStateForTransplant(statePath, platformContext);
        System.out.printf(
                "Transplant complete. %d events were discarded due to being from a future round.%n",
                discardedEventCount);

        return RETURN_CODE_SUCCESS;
    }
}

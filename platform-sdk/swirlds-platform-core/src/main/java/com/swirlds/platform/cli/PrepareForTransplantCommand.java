package com.swirlds.platform.cli;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;

import com.swirlds.base.time.Time;
import com.swirlds.cli.commands.StateCommand;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.io.utility.SimpleRecycleBin;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.config.DefaultConfiguration;
import com.swirlds.platform.event.preconsensus.CommonPcesWriter;
import com.swirlds.platform.event.preconsensus.PcesConfig;
import com.swirlds.platform.event.preconsensus.PcesFileManager;
import com.swirlds.platform.event.preconsensus.PcesFileReader;
import com.swirlds.platform.event.preconsensus.PcesFileTracker;
import com.swirlds.platform.event.preconsensus.PcesMultiFileIterator;
import com.swirlds.platform.state.snapshot.SavedStateMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
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
    /** The temporary directory to move PCES files to while there are being filtered out */
    private static final String PCES_TEMPORARY_DIR = "pces-tmp";

    /** The path to the state to prepare for transplant. */
    private Path statePath;

    /** Load configuration from these files. */
    private List<Path> configurationPaths = List.of();

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
     * Set the configuration paths.
     */
    @CommandLine.Option(
            names = {"-c", "--config"},
            description = "A path to where a configuration file can be found. If not provided then defaults are used")
    @SuppressWarnings("unused") // used by picocli
    private void setConfigurationPath(final List<Path> configurationPaths) {
        configurationPaths.forEach(this::pathMustExist);
        this.configurationPaths = configurationPaths;
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
            try (final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                response = reader.readLine();
            }
            if (!response.toUpperCase().startsWith("Y")) {
                System.out.println("Operation aborted.");
            }
            return RETURN_CODE_PROMPT_NO;
        }

        final Configuration configuration = DefaultConfiguration.buildBasicConfiguration(
                ConfigurationBuilder.create(), getAbsolutePath("settings.txt"), configurationPaths);

        final PlatformContext platformContext = PlatformContext.create(
                configuration,
                Time.getCurrent(),
                new NoOpMetrics(),
                FileSystemManager.create(configuration),
                new SimpleRecycleBin(),
                MerkleCryptographyFactory.create(configuration)
        );

        System.out.println("Transplanting state from: " + statePath);
        prepareStateForTransplant(statePath, platformContext);

        return RETURN_CODE_SUCCESS;
    }

    /**
     * Prepares the state for transplanting by removing future events from the PCES files.
     *
     * @param statePath       the path to the state directory
     * @param platformContext the platform context
     * @throws IOException if an I/O error occurs
     */
    public static void prepareStateForTransplant(@NonNull final Path statePath, @NonNull final PlatformContext platformContext)
            throws IOException {
        final Path pcesFiles = statePath.resolve(
                platformContext.getConfiguration().getConfigData(PcesConfig.class).databaseDirectory());
        final Path pcesTmp = statePath.resolve(PCES_TEMPORARY_DIR);

        // move the old files to a temporary directory
        Files.move(pcesFiles, pcesTmp, StandardCopyOption.REPLACE_EXISTING);

        final SavedStateMetadata stateMetadata = SavedStateMetadata.parse(
                statePath.resolve(SavedStateMetadata.FILE_NAME)
        );

        final PcesFileTracker fileTracker = PcesFileReader.readFilesFromDisk(
                platformContext,
                pcesTmp,
                stateMetadata.round(),
                false
        );

        final PcesMultiFileIterator eventIterator = fileTracker.getEventIterator(
                stateMetadata.minimumBirthRoundNonAncient(), stateMetadata.round());
        final CommonPcesWriter pcesWriter = new CommonPcesWriter(
                platformContext,
                new PcesFileManager(
                        platformContext,
                        new PcesFileTracker(),
                        pcesFiles,
                        stateMetadata.round()
                )
        );
        pcesWriter.beginStreamingNewEvents();

        // Go through the events and write them to the new files, skipping any events that are from a future round
        int discardedEventCount = 0;
        while (eventIterator.hasNext()) {
            final PlatformEvent event = eventIterator.next();
            if (event.getBirthRound() > stateMetadata.round()) {
                discardedEventCount++;
                continue;
            }
            pcesWriter.prepareOutputStream(event);
            pcesWriter.getCurrentMutableFile().writeEvent(event);
        }
        pcesWriter.closeCurrentMutableFile();

        FileUtils.deleteDirectory(pcesTmp);

        System.out.printf("Transplant complete. %d events were discarded due to being from a future round.%n",
                discardedEventCount);
    }
}



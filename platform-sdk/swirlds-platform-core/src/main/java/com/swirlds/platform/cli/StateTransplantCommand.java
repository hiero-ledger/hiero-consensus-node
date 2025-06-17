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
        description = "Prepare a state for transplanting to another network.")
@SubcommandOf(StateCommand.class)
public class StateTransplantCommand extends AbstractCommand {
    private static final int RETURN_CODE_SUCCESS = 0;
    private static final int RETURN_CODE_ABORTED = 1;

    private Path statePath;

    /**
     * Load configuration from these files.
     */
    private List<Path> configurationPaths = List.of();

    @CommandLine.Option(
            names = {"-ac", "--auto-confirm"},
            description = "Automatically confirm the operation without prompting."
    )
    @SuppressWarnings("unused") // used by picocli
    private boolean autoConfirm;

    /**
     * Set the path to state A.
     */
    @CommandLine.Parameters(description = "The path to the state to transplant")
    @SuppressWarnings("unused") // used by picocli
    private void setStatePath(final Path statePath) {
        this.statePath = pathMustExist(statePath.toAbsolutePath());
    }

    /**
     * Set the configuration paths.
     */
    @CommandLine.Option(
            names = {"-c", "--config"},
            description = "A path to where a configuration file can be found. If not provided then defaults are used.")
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
            System.out.println("Warning: This action may have consequences.");
            System.out.println("Do you want to continue? (Y/N): ");

            final String response;
            try(final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))){
                response = reader.readLine();
            }
            if (!response.toUpperCase().startsWith("Y")) {
                System.out.println("Operation aborted.");
            }
            return  RETURN_CODE_ABORTED;
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
        transplantState(statePath, platformContext);

        return RETURN_CODE_SUCCESS;
    }

    public static void transplantState(final Path statePath, final PlatformContext platformContext)
            throws IOException {
        final Path pcesFiles = statePath.resolve(platformContext.getConfiguration().getConfigData(PcesConfig.class).databaseDirectory());
        final Path pcesTmp = statePath.resolve("pces-tmp");

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
                platformContext.getConfiguration(),
                new PcesFileManager(
                        platformContext,
                        new PcesFileTracker(),
                        pcesFiles,
                        stateMetadata.round()
                )
        );
        pcesWriter.beginStreamingNewEvents();

        int discardedEventCount = 0;
        while (eventIterator.hasNext()) {
            final PlatformEvent event = eventIterator.next();
            if(event.getBirthRound() > stateMetadata.round()){
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



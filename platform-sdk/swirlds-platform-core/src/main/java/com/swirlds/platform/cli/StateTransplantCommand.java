package com.swirlds.platform.cli;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;

import com.swirlds.cli.commands.StateCommand;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.io.utility.SimpleRecycleBin;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.config.DefaultConfiguration;
import com.swirlds.platform.event.preconsensus.PcesFileReader;
import com.swirlds.platform.event.preconsensus.PcesMultiFileIterator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine;

@CommandLine.Command(
        name = "transplant",
        mixinStandardHelpOptions = true,
        description = "Prepare a state for transplanting to another network.")
@SubcommandOf(StateCommand.class)
public class StateTransplantCommand extends AbstractCommand {

    private Path statePath;

    /**
     * Load configuration from these files.
     */
    private List<Path> configurationPaths = List.of();

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
        final Configuration configuration = DefaultConfiguration.buildBasicConfiguration(
                ConfigurationBuilder.create(), getAbsolutePath("settings.txt"), configurationPaths);

        transplantState(statePath, configuration, new SimpleRecycleBin());

        return 0;
    }

    public static void transplantState(final Path statePath, final Configuration configuration, final RecycleBin recycleBin)
            throws IOException {
        // Logic to transplant state goes here
        // This is a placeholder for the actual implementation
        System.out.println("Transplanting state from: " + statePath);

        PcesFileReader.readFilesFromDisk(
                configuration,
                recycleBin,
                statePath,
                0,
                true // permit gaps
        );

    }
}

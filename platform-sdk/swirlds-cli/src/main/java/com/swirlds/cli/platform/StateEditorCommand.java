// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.platform;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;

import com.swirlds.cli.commands.StateCommand;
import com.swirlds.cli.platform.state.editor.StateEditor;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.config.DefaultConfiguration;
import com.swirlds.platform.util.BootstrapUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine;

@CommandLine.Command(
        name = "editor",
        mixinStandardHelpOptions = true,
        description = "Launch an interactive state editor.")
@SubcommandOf(StateCommand.class)
public class StateEditorCommand extends AbstractCommand {

    private Path statePath;

    /**
     * Load configuration from these files.
     */
    private List<Path> configurationPaths = List.of();

    /**
     * Set the path to state A.
     */
    @CommandLine.Parameters(description = "The path to the state to open with the editor")
    private void setStatePath(final Path statePath) {
        this.statePath = pathMustExist(statePath.toAbsolutePath());
    }

    /**
     * Set the configuration paths.
     */
    @CommandLine.Option(
            names = {"-c", "--config"},
            description = "A path to where a configuration file can be found. If not provided then defaults are used.")
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
        DefaultConfiguration.buildBasicConfiguration(
                ConfigurationBuilder.create(), getAbsolutePath("settings.txt"), configurationPaths);
        BootstrapUtils.setupConstructableRegistry();

        new StateEditor(statePath).start();

        return 0;
    }
}

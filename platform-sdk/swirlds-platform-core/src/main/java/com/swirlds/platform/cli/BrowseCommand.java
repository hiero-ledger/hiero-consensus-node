// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli;

import static com.swirlds.platform.system.SystemExitCode.CONFIGURATION_ERROR;
import static com.swirlds.platform.system.SystemExitCode.FATAL_ERROR;
import static java.util.concurrent.TimeUnit.MINUTES;

import com.swirlds.cli.PlatformCli;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.platform.NodeId;
import com.swirlds.common.utility.CommonUtils;
import com.swirlds.platform.Browser;
import com.swirlds.platform.CommandLineArgs;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "browse",
        mixinStandardHelpOptions = true,
        description = "Launch local instances of the platform using the Browser UI. "
                + "Note: the Browser UI expects a very specific file system layout. Such a layout is present in the "
                + " hedera-services/platform-sdk/sdk/ directory.")
@SubcommandOf(PlatformCli.class)
public class BrowseCommand extends AbstractCommand {

    private List<NodeId> localNodes = new ArrayList<>();

    /**
     * If true, perform a clean operation before starting.
     */
    private boolean clean = false;

    @CommandLine.Option(
            names = {"-l", "--local-node"},
            description = "Specify a node that should be run in this JVM. If no nodes are provided, "
                    + "all nodes with local IP addresses are loaded in this JVM. Multiple nodes can be "
                    + "specified by repeating the parameter `-l #1 -l #2 -l #3`.")
    private void setLocalNodes(@NonNull final Long... localNodes) {
        for (final Long nodeId : localNodes) {
            this.localNodes.add(NodeId.of(nodeId));
        }
    }

    @CommandLine.Option(
            names = {"-c", "--clean"},
            description = "Perform a clean operation before starting.")
    private void clean(final boolean clean) {
        this.clean = clean;
    }

    /**
     * This method is called after command line input is parsed.
     *
     * @return return code of the program
     */
    @SuppressWarnings("InfiniteLoopStatement")
    @Override
    public Integer call() throws IOException, InterruptedException {
        final Path sdkPath = Path.of(System.getProperty("user.dir"));
        if (clean) {
            CleanCommand.clean(sdkPath);
        }

        if (!hasPemFile(sdkPath.resolve("data/keys/"))) {
            CommonUtils.tellUserConsole("please generate keys with generate-keys command first");
            return CONFIGURATION_ERROR.getExitCode();
        }

        try {
            Browser.launch(new CommandLineArgs(new HashSet<>(localNodes)), false);
        } catch (final Exception e) {
            e.printStackTrace();
            return FATAL_ERROR.getExitCode();
        }

        // Sleep forever to keep the process alive.
        while (true) {
            MINUTES.sleep(1);
        }
    }

    /**
     * Checks if any .pem file exists in the given directory and its subdirectories.
     *
     * @param directoryPath The path of the directory to search in.
     * @return True if at least one .pem file exists, false otherwise.
     * @throws IOException If an I/O error occurs.
     */
    public static boolean hasPemFile(@NonNull Path directoryPath) throws IOException {
        return Objects.requireNonNull(directoryPath.toFile().listFiles((dir, name) -> name.endsWith(".pem"))).length>0;
    }
}

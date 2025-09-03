// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.platform;

import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.platform.event.preconsensus.PcesUtilities;
import com.swirlds.platform.util.BootstrapUtils;
import java.nio.file.Path;
import picocli.CommandLine;

@CommandLine.Command(
        name = "compact",
        mixinStandardHelpOptions = true,
        description = "Compact the generational span of all PCES files in a given directory tree.")
@SubcommandOf(PcesCommand.class)
public class PcesCompactCommand extends AbstractCommand {

    private Path rootDirectory;

    @CommandLine.Parameters(description = "The root directory of the PCES files to compact.")
    private void setRootDirectory(final Path rootDirectory) {
        this.rootDirectory = pathMustExist(rootDirectory);
    }

    /**
     * Entry point for program.
     */
    @Override
    public Integer call() {
        BootstrapUtils.setupConstructableRegistry();
        PcesUtilities.compactPreconsensusEventFiles(rootDirectory);
        return 0;
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli;

import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.platform.Browser;
import com.swirlds.platform.CommandLineArgs;
import java.io.IOException;
import java.util.Set;
import org.hiero.consensus.model.node.NodeId;
import picocli.CommandLine;

@CommandLine.Command(
        name = "recover",
        mixinStandardHelpOptions = true,
        description = "Start the platform in PCES recovery mode. The platform will replay events from disk, "
                + "save a state, then shut down.")
@SubcommandOf(PcesCommand.class)
public class PcesRecoveryCommand extends AbstractCommand {
    @CommandLine.Parameters(description = "The node ID to run in recovery mode", index = "0")
    private long nodeId;

    @Override
    public Integer call() throws IOException, InterruptedException {
        Browser.launch(new CommandLineArgs(Set.of(NodeId.of(nodeId))), true);
        return null;
    }
}

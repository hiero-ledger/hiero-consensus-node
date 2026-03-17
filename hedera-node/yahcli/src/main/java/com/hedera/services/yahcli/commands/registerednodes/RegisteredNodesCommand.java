// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.registerednodes;

import com.hedera.services.yahcli.Yahcli;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.ParentCommand;

@CommandLine.Command(
        name = "registeredNodes",
        subcommands = {
            CreateRegisteredNodeCommand.class,
            UpdateRegisteredNodeCommand.class,
            DeleteRegisteredNodeCommand.class
        },
        description = "Performs DAB registered-nodes operations")
public class RegisteredNodesCommand implements Callable<Integer> {
    @ParentCommand
    Yahcli yahcli;

    @Override
    public Integer call() throws Exception {
        throw new CommandLine.ParameterException(
                yahcli.getSpec().commandLine(), "Please specify a registeredNodes subcommand");
    }

    public Yahcli getYahcli() {
        return yahcli;
    }

    long validatedNodeId(@NonNull final String nodeId) {
        try {
            final long id = Long.parseLong(nodeId);
            if (id < 0) {
                throw new IllegalArgumentException("Negative node id");
            }
            return id;
        } catch (Exception e) {
            throw new CommandLine.ParameterException(
                    yahcli.getSpec().commandLine(), "Invalid registered node id '" + nodeId + "'");
        }
    }
}

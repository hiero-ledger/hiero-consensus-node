// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.keys;

import com.hedera.services.yahcli.Yahcli;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(
        name = "keys",
        subcommands = {
            picocli.CommandLine.HelpCommand.class,
            NewPemCommand.class,
            ExtractPublicCommand.class,
            ExtractDetailsCommand.class
        },
        description = "Generates and inspects keys of various kinds")
public class KeysCommand implements Callable<Integer> {

    @ParentCommand
    Yahcli yahcli;

    @Override
    public Integer call() throws Exception {
        throw new picocli.CommandLine.ParameterException(
                yahcli.getSpec().commandLine(), "Please specify an keys subcommand!");
    }

    public Yahcli getYahcli() {
        return yahcli;
    }
}

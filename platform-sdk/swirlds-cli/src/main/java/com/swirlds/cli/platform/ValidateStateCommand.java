// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.platform;

import com.swirlds.cli.commands.StateCommand;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.logging.legacy.LogMarker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;

@CommandLine.Command(
        name = "validate",
        mixinStandardHelpOptions = true,
        description = "Validate data integrity of a state file.")
@SubcommandOf(StateCommand.class)
public final class ValidateStateCommand extends AbstractCommand {
    private static final Logger logger = LogManager.getLogger(ValidateStateCommand.class);

    private ValidateStateCommand() {}

    @Override
    public Integer call() {
        logger.info(LogMarker.CLI.getMarker(), "This command is a work in progress (Deepak)");
        return 0;
    }
}

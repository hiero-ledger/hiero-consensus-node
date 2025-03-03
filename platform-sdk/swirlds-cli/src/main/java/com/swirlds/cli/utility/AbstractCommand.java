// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.utility;

import java.util.concurrent.Callable;

/**
 * Contains boilerplate for commands.
 */
public abstract class AbstractCommand extends ParameterizedClass implements Callable<Integer> {

    /**
     * A default call method. Only needs to be overridden by commands with no subcommands.
     */
    @Override
    public Integer call() throws Exception {
        throw buildParameterException("no subcommand provided");
    }
}

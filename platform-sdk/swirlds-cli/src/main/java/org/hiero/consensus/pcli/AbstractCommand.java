// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli;

import java.util.concurrent.Callable;
import org.hiero.consensus.pcli.utility.ParameterizedClass;

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

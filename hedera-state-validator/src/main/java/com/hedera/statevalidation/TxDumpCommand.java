// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import com.hedera.statevalidation.blockstream.tools.TxDump;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Prints the block-stream content of a round: a summary of every transaction, or the full body, result,
 * outputs and state changes of one transaction selected by txId or position. Does not load a state.
 */
@Command(
        name = "tx-dump",
        mixinStandardHelpOptions = true,
        description = "Print the transactions of a round, or the full block content of one transaction.")
public class TxDumpCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    @SuppressWarnings("unused")
    private StateOperatorCommand parent;

    @Spec
    private CommandSpec spec;

    @Option(names = "--blocks", required = true, description = "A block file, or a directory of block files.")
    private Path blocks;

    @Option(names = "--round", required = true, description = "Round to inspect.")
    private long round;

    @Option(names = "--tx", description = "Print every transaction with this txId (may match more than one).")
    private String tx;

    @Option(names = "--pos", description = "Print the transaction at this 0-based position in the round.")
    private Integer pos;

    @Override
    public Integer call() throws Exception {
        if (tx != null && pos != null) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Use either --tx or --pos, not both");
        }
        final List<String> args =
                new ArrayList<>(List.of("--blocks", blocks.toString(), "--round", Long.toString(round)));
        if (tx != null) {
            args.add("--tx");
            args.add(tx);
        }
        if (pos != null) {
            args.add("--pos");
            args.add(pos.toString());
        }
        TxDump.main(args.toArray(String[]::new));
        return 0;
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import com.hedera.statevalidation.blockstream.tools.OutputRecordCompare;
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
 * Compares full transaction output records (result, outputs, net state changes) between an original and a
 * re-minted block stream, and reports the first (or every) divergent transaction. Does not load a state.
 */
@Command(
        name = "output-record-compare",
        mixinStandardHelpOptions = true,
        description = "Find the first (or every) transaction whose full output record differs between the "
                + "original and the re-minted block stream.")
public class OutputRecordCompareCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    @SuppressWarnings("unused")
    private StateOperatorCommand parent;

    @Spec
    private CommandSpec spec;

    @Option(
            names = "--original",
            required = true,
            description = "Directory with the original (production) block files.")
    private Path originalDir;

    @Option(names = "--reminted", required = true, description = "Directory with the re-minted block files.")
    private Path remintedDir;

    @Option(names = "--round", description = "Single-round mode: report every divergent transaction in this round.")
    private Long round;

    @Option(
            names = "--from-round",
            description = "Range mode: lowest round to compare. Default = start of the block range.")
    private Long fromRound;

    @Option(
            names = "--to-round",
            description = "Range mode: highest round to compare. Default = end of the block range.")
    private Long toRound;

    @Option(names = "--all", description = "Range mode: report every divergent block instead of stopping at the first.")
    private boolean all;

    @Option(
            names = "--ignore-states",
            split = ",",
            description = "Services to ignore when comparing state changes, e.g. "
                    + "BlockStreamService,PlatformStateService,BlockRecordService.")
    private List<String> ignoreStates;

    @Option(names = "--dump-tx", description = "With --round: print the differing values for this txId.")
    private String dumpTx;

    @Option(names = "--threads", description = "Worker threads for range mode. Default = available processors.")
    private Integer threads;

    @Option(names = "--window", description = "Blocks per parallel window in range mode. Default = threads * 8.")
    private Integer window;

    @Override
    public Integer call() throws Exception {
        if (round != null && (fromRound != null || toRound != null || all)) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "--round cannot be combined with --from-round, --to-round or --all");
        }
        if (dumpTx != null && round == null) {
            throw new CommandLine.ParameterException(spec.commandLine(), "--dump-tx requires --round");
        }

        final List<String> args = new ArrayList<>();
        add(args, "--original", originalDir.toString());
        add(args, "--reminted", remintedDir.toString());
        add(args, "--round", round);
        add(args, "--from-round", fromRound);
        add(args, "--to-round", toRound);
        if (all) {
            args.add("--all");
        }
        if (ignoreStates != null && !ignoreStates.isEmpty()) {
            add(args, "--ignore-states", String.join(",", ignoreStates));
        }
        add(args, "--dump-tx", dumpTx);
        add(args, "--threads", threads);
        add(args, "--window", window);

        OutputRecordCompare.main(args.toArray(String[]::new));
        return 0;
    }

    private static void add(final List<String> args, final String flag, final Object value) {
        if (value != null) {
            args.add(flag);
            args.add(value.toString());
        }
    }
}

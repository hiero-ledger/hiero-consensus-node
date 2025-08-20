// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import com.hedera.statevalidation.exporters.SortedJsonExporter;
import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.swirlds.common.utility.Labeled;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.state.merkle.MerkleStateRoot;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import picocli.CommandLine;

@CommandLine.Command(name = "export", description = "Exports the state")
public class ExportCommand implements Runnable {

    private static final int MAX_PARALLEL_EXPORTS = 10;
    public static final int MAX_OBJ_PER_FILE = Integer.parseInt(System.getProperty("maxObjPerFile", "1000000"));
    public static final boolean PRETTY_PRINT_ENABLED = Boolean.parseBoolean(System.getProperty("prettyPrint", "false"));

    @CommandLine.ParentCommand
    private StateOperatorCommand parent;

    @CommandLine.Parameters(index = "0", arity = "1", description = "Result directory")
    private String resultDirStr;

    @CommandLine.Parameters(index = "1", arity = "0..1", description = "Service name")
    private String serviceName;

    @CommandLine.Parameters(index = "2", arity = "0..1", description = "State name")
    private String stateName;

    @Override
    public void run() {
        System.setProperty("state.dir", parent.getStateDir().getAbsolutePath());
        File resultDir = new File(resultDirStr);
        if (!resultDir.exists()) {
            throw new RuntimeException(resultDir.getAbsolutePath() + " does not exist");
        }
        if (!resultDir.isDirectory()) {
            throw new RuntimeException(resultDir.getAbsolutePath() + " is not a directory");
        }

        MerkleDb.setDefaultPath(parent.getStateDir().toPath());
        try {
            StateResolver.initState();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        final MerkleStateRoot state = (MerkleStateRoot) StateResolver.getState();
        if (serviceName == null) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            ExecutorService executorService = Executors.newFixedThreadPool(MAX_PARALLEL_EXPORTS);
            for (int i = 0; i < state.getNumberOfChildren(); i++) {
                final Labeled labeled = state.getChild(i);
                if (labeled == null) {
                    continue;
                }
                String[] serviceNameAndStateKey = labeled.getLabel().split("[.]");
                futures.add(CompletableFuture.runAsync(
                        () -> {
                            try {
                                final SortedJsonExporter exporter = new SortedJsonExporter(
                                        resultDir, state, serviceNameAndStateKey[0], serviceNameAndStateKey[1]);
                                exporter.export();
                            } catch (IllegalArgumentException e) {
                                System.out.println(e.getMessage());
                            }
                        },
                        executorService));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } else {
            final SortedJsonExporter exporter = new SortedJsonExporter(resultDir, state, serviceName, stateName);
            exporter.export();
        }
    }
}

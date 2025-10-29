// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.statevalidation.exporter.SortedJsonExporter;
import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.base.utility.Pair;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "sorted-export", description = "Exports the state in a sorted way.")
public class SortedExportCommand implements Runnable {

    private static final Logger log = LogManager.getLogger(SortedExportCommand.class);

    @ParentCommand
    private StateOperatorCommand parent;

    @Option(
            names = {"-s", "--service-name"},
            description = "Service name.")
    private String serviceName;

    @Option(
            names = {"-k", "--state-key"},
            description = "State key.")
    private String stateKey;

    @Option(
            names = {"-f", "--first-leaf-path"},
            defaultValue = "-1",
            description =
                    "First leaf path in the export range (inclusive). If not specified, defaults to the actual first leaf path in the supplied state. Must be within the range of actual leaf paths in the supplied state.")
    private long firstLeafPath;

    @Option(
            names = {"-l", "--last-leaf-path"},
            defaultValue = "-1",
            description =
                    "Last leaf path in the export range (inclusive). If not specified, defaults to the actual last leaf path in the supplied state. Must be within the range of actual leaf paths in the supplied state.")
    private long lastLeafPath;

    @Option(
            names = {"-o", "--out"},
            required = true,
            description = "Directory where the exported JSON files are written. Must exist before invocation.")
    private String outputDirStr;

    @Override
    public void run() {
        parent.initializeStateDir();
        final File outputDir = new File(outputDirStr);
        if (!outputDir.exists()) {
            throw new RuntimeException(outputDir.getAbsolutePath() + " does not exist");
        }
        if (!outputDir.isDirectory()) {
            throw new RuntimeException(outputDir.getAbsolutePath() + " is not a directory");
        }

        final MerkleNodeState state;
        log.info("Initializing the state...");
        long start = System.currentTimeMillis();
        try {
            final DeserializedSignedState deserializedSignedState = StateUtils.getDeserializedSignedState();
            state = deserializedSignedState.reservedSignedState().get().getState();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        log.info("State has been initialized in {} seconds.", (System.currentTimeMillis() - start) / 1000);

        ((VirtualMap) state.getRoot()).getDataSource().stopAndDisableBackgroundCompaction();

        if (serviceName == null) {
            // processing all
            final SortedJsonExporter exporter = new SortedJsonExporter(
                    outputDir, state, prepareServiceNamesAndStateKeys(), firstLeafPath, lastLeafPath);
            exporter.export();
        } else {
            final SortedJsonExporter exporter =
                    new SortedJsonExporter(outputDir, state, serviceName, stateKey, firstLeafPath, lastLeafPath);
            exporter.export();
        }
        log.info("Total time is {} seconds.", (System.currentTimeMillis() - start) / 1000);
    }

    private static List<Pair<String, String>> prepareServiceNamesAndStateKeys() {
        final List<Pair<String, String>> serviceNamesAndStateKeys = new ArrayList<>();
        for (final StateKey.KeyOneOfType value : StateKey.KeyOneOfType.values()) {
            extractStateName(value.protoName(), serviceNamesAndStateKeys);
        }
        for (final SingletonType singletonType : SingletonType.values()) {
            extractStateName(singletonType.protoName(), serviceNamesAndStateKeys);
        }

        return serviceNamesAndStateKeys;
    }

    private static void extractStateName(
            @NonNull final String value, @NonNull final List<Pair<String, String>> serviceNamesAndStateKeys) {
        final String[] serviceNameStateKey = value.split("_I_");
        if (serviceNameStateKey.length == 2) {
            serviceNamesAndStateKeys.add(Pair.of(serviceNameStateKey[0], serviceNameStateKey[1]));
        }
    }
}

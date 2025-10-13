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
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(name = "sorted-export", description = "Exports the state in a sorted way.")
public class SortedExportCommand implements Runnable {

    private static final Logger log = LogManager.getLogger(SortedExportCommand.class);

    @ParentCommand
    private StateOperatorCommand parent;

    @Parameters(index = "0", arity = "1", description = "Result directory.")
    private String resultDirStr;

    @Parameters(index = "1", arity = "0..1", description = "Service name.")
    private String serviceName;

    @Parameters(index = "2", arity = "0..1", description = "State key.")
    private String stateKey;

    @Override
    public void run() {
        parent.initializeStateDir();
        final File resultDir = new File(resultDirStr);
        if (!resultDir.exists()) {
            throw new RuntimeException(resultDir.getAbsolutePath() + " does not exist");
        }
        if (!resultDir.isDirectory()) {
            throw new RuntimeException(resultDir.getAbsolutePath() + " is not a directory");
        }

        final MerkleNodeState state;
        log.info("Initializing the state...");
        long start = System.currentTimeMillis();
        try {
            DeserializedSignedState deserializedSignedState = StateUtils.getDeserializedSignedState();
            state = deserializedSignedState.reservedSignedState().get().getState();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        log.info("State has been initialized in {} seconds.", (System.currentTimeMillis() - start) / 1000);

        ((VirtualMap) state.getRoot()).getDataSource().stopAndDisableBackgroundCompaction();

        if (serviceName == null) {
            // processing all
            final SortedJsonExporter exporter =
                    new SortedJsonExporter(resultDir, state, prepareServiceNamesAndStateKeys());
            exporter.export();
        } else {
            final SortedJsonExporter exporter = new SortedJsonExporter(resultDir, state, serviceName, stateKey);
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

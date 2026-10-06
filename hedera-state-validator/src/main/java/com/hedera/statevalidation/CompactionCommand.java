// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import static com.hedera.statevalidation.compaction.Compaction.runCompaction;

import com.hedera.statevalidation.util.StateUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(name = "compact", description = "Performs compaction of state files.")
public class CompactionCommand implements Runnable {

    @ParentCommand
    private StateOperatorCommand parent;

    private CompactionCommand() {}

    @Override
    public void run() {
        parent.initializeStateDir();
        try {
            runCompaction(StateUtils.getDefaultState());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

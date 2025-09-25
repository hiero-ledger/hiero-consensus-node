// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import com.hedera.statevalidation.validators.ValidationEngine;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

/**
 * This class is an entry point for the validators.
 * It is responsible for discovering all tests in the package and running them. Uses provided tags to filter tests.<br>
 * All validators are expecting 2 parameters:<br>
 * 1. State directory - the directory where the state is stored<br>
 * 2. Tag to run - the tag of the test to run (optional) If no tags are provided, all tests are run.<br>
 */
@Command(
        name = "validate",
        mixinStandardHelpOptions = true,
        description = "Validates the state of a Mainnet Hedera node")
public class ValidateCommand implements Callable<Integer> {

    @ParentCommand
    private StateOperatorCommand parent;

    @CommandLine.Parameters(
            arity = "1..*",
            description =
                    "Tag to run: [stateAnalyzer, internal, leaf, hdhm, account, tokenRelations, rehash, files, compaction, entityIds]")
    private String[] tags = {
        "stateAnalyzer",
        "internal",
        "leaf",
        "hdhm",
        "account",
        "tokenRelations",
        "rehash",
        "files",
        "compaction",
        "entityIds"
    };

    @Override
    public Integer call() {
        System.setProperty("state.dir", parent.getStateDir().getAbsolutePath());

        try {
            ValidationEngine engine = new ValidationEngine();
            engine.execute(tags);
            return 0;
        } catch (Exception e) {
            return 1;
        }
    }
}

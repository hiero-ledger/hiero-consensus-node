// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import static com.hedera.statevalidation.validators.merkledb.ValidateLeafIndex.LEAF;
import static com.hedera.statevalidation.validators.merkledb.ValidateLeafIndexHalfDiskHashMap.HDHM;
import static com.hedera.statevalidation.validators.servicesstate.AccountValidator.ACCOUNT;

import com.hedera.statevalidation.listener.LoggingTestExecutionListenerPoc;
import com.hedera.statevalidation.listener.SummaryGeneratingListenerPoc;
import com.hedera.statevalidation.reporting.JsonHelper;
import com.hedera.statevalidation.reporting.ReportingFactory;
import com.hedera.statevalidation.validators.ValidationEngine;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

    private static final Logger log = LogManager.getLogger(ValidateCommand.class);

    @ParentCommand
    private StateOperatorCommand parent;

    @CommandLine.Parameters(
            arity = "1..*",
            description =
                    "Tag to run: [stateAnalyzer, internal, leaf, hdhm, account, tokenRelations, rehash, files, compaction, entityIds]")
    private String[] tags = {
        "stateAnalyzer", "internal", LEAF, HDHM, ACCOUNT, "tokenRelations", "rehash", "files", "compaction", "entityIds"
    };

    @Override
    public Integer call() {
        System.setProperty("state.dir", parent.getStateDir().getAbsolutePath());

        try {
            SummaryGeneratingListenerPoc summaryGeneratingListener = new SummaryGeneratingListenerPoc();
            ValidationEngine engine = new ValidationEngine();
            engine.addListener(new LoggingTestExecutionListenerPoc());
            engine.addListener(summaryGeneratingListener);
            engine.execute(tags);

            // Code from ReportingListener can be inlined,
            // as anyway it was called after all validations, from `testPlanExecutionFinished`
            log.info(
                    "Writing JSON report to [{}]",
                    Constants.REPORT_FILE.toAbsolutePath().toString());
            JsonHelper.writeReport(ReportingFactory.getInstance().report(), Constants.REPORT_FILE);

            return summaryGeneratingListener.isFailed() ? 1 : 0;
        } catch (Exception e) {
            return 1;
        }
    }
}

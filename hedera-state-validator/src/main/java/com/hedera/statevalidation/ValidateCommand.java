// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

import com.hedera.statevalidation.listener.LoggingTestExecutionListener;
import com.hedera.statevalidation.listener.SummaryGeneratingListener;
import java.util.concurrent.Callable;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.TagFilter;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
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
            description = "Tag to run: [internal, leaf, hdhm, account, tokenRelations, rehash, files, entityIds]")
    private String[] tags = {"internal", "leaf", "hdhm", "account", "tokenRelations", "rehash", "files", "entityIds"};

    @Override
    public Integer call() {
        System.setProperty("state.dir", parent.getStateDir().getAbsolutePath());

        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectPackage("com.hedera.statevalidation.validators"))
                .filters(TagFilter.includeTags(tags))
                .build();

        TestPlan testPlan;
        SummaryGeneratingListener summaryGeneratingListener = new SummaryGeneratingListener();
        try (LauncherSession session = LauncherFactory.openSession()) {
            Launcher launcher = session.getLauncher();
            launcher.registerTestExecutionListeners(summaryGeneratingListener, new LoggingTestExecutionListener());
            testPlan = launcher.discover(request);
            launcher.execute(testPlan);
        }

        return summaryGeneratingListener.isFailed() ? 1 : 0;
    }
}

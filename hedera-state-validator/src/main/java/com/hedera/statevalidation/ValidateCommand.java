/*
 * Copyright (C) 2022-2023 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.statevalidation;

import com.hedera.statevalidation.listener.LoggingTestExecutionListener;
import com.hedera.statevalidation.listener.ReportingListener;
import com.hedera.statevalidation.listener.SummaryGeneratingListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

import java.io.File;
import java.util.concurrent.Callable;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

/**
 * This class is an entry point for the validators.
 * It is responsible for discovering all tests in the package and running them. Uses provided tags to filter tests.<br>
 * All validators are expecting 2 parameters:<br>
 * 1. State directory - the directory where the state is stored<br>
 * 2. Tag to run - the tag of the test to run (optional) If no tags are provided, all tests are run.<br>
 */
@Command(name = "validate", mixinStandardHelpOptions = true,
        description = "Validates the state of a Mainnet Hedera node")
public class ValidateCommand implements Callable<Integer> {

    @ParentCommand
    private StateOperatorCommand parent;

    @CommandLine.Parameters(arity = "1..*", description = "Tag to run: [stateAnalyzer, internal, leaf, hdhm, account, tokenRelations, rehash, files, compaction]")
    private String[] tags = {"stateAnalyzer", "internal", "leaf", "hdhm", "account", "tokenRelations", "rehash", "files", "compaction"};

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
            launcher.registerTestExecutionListeners(new ReportingListener(), summaryGeneratingListener, new LoggingTestExecutionListener());
            testPlan = launcher.discover(request);
            launcher.execute(testPlan);
        }

        return summaryGeneratingListener.isFailed() ? 1 : 0;
    }

}
// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.report;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class SlackReportGenerator implements AfterTestExecutionCallback {

    private static final List<SlackReportBuilder.ValidationFailure> failures = new ArrayList<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> SlackReportBuilder.generateReport(failures)));
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        if (context.getExecutionException().isPresent()) {
            failures.add(new SlackReportBuilder.ValidationFailure(
                    context.getDisplayName(),
                    context.getExecutionException().get().getMessage()));
        }
    }
}

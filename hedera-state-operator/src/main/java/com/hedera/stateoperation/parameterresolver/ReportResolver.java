// SPDX-License-Identifier: Apache-2.0
package com.hedera.stateoperation.parameterresolver;

import com.hedera.stateoperation.reporting.Report;
import com.hedera.stateoperation.reporting.ReportingFactory;
import org.junit.jupiter.api.extension.*;

public class ReportResolver implements ParameterResolver {
    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return parameterContext.getParameter().getType() == Report.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return ReportingFactory.getInstance().report();
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.stateoperator.parameterresolver;

import com.hedera.stateoperator.reporting.Report;
import com.hedera.stateoperator.reporting.ReportingFactory;
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

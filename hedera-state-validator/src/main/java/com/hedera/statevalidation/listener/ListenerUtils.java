// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.listener;

import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;

public class ListenerUtils {

    public static String extractTestFullName(TestIdentifier testIdentifier) {
        final MethodSource methodSource =
                (MethodSource) testIdentifier.getSource().orElseThrow();
        final String methodName = methodSource.getMethodName();
        final String className = methodSource.getJavaClass().getSimpleName();
        final String displayName = testIdentifier.getDisplayName();
        return String.format("%s.%s,%s", className, methodName, displayName);
    }
}

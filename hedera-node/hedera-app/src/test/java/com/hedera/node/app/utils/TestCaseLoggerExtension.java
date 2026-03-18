// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.utils;

import java.lang.reflect.Method;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit Extension that generates a log statement before and after each test case. This allows for easier identification
 * of logs relating to specific test cases.
 */
public class TestCaseLoggerExtension implements BeforeEachCallback, AfterEachCallback {

    private static final Logger logger = LogManager.getLogger(TestCaseLoggerExtension.class);

    @Override
    public void beforeEach(final ExtensionContext ctx) throws Exception {
        final String className = ctx.getTestClass().map(Class::getSimpleName).orElse("Unknown");
        final String methodName = ctx.getTestMethod().map(Method::getName).orElse("unknown");
        final String msg = "--- START " + className + "#" + methodName + " -->";
        logger.info(msg);
    }

    @Override
    public void afterEach(final ExtensionContext ctx) throws Exception {
        final String className = ctx.getTestClass().map(Class::getSimpleName).orElse("Unknown");
        final String methodName = ctx.getTestMethod().map(Method::getName).orElse("unknown");
        final String msg = "<-- COMPLETE " + className + "#" + methodName + " ---";
        logger.info(msg);
    }
}

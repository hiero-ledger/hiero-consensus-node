// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.fixtures.context.internal;

import com.swirlds.base.context.internal.ThreadLocalContext;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit extension to clear the thread local context before and after each test.
 */
public class ThreadLocalContextExtension implements BeforeEachCallback, AfterEachCallback {

    private static final ThreadLocal<Map<String, String>> savedSate = new ThreadLocal<>();

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        savedSate.set(ThreadLocalContext.getContextMap());
        ThreadLocalContext.getInstance().clear();
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        ThreadLocalContext.getInstance().clear();
        Optional.ofNullable(savedSate.get()).ifPresent(map -> map.forEach(ThreadLocalContext.getInstance()::add));
    }
}

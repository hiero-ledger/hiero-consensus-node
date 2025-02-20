// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.fixtures.context.internal;

import com.swirlds.base.context.internal.GlobalContext;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit extension to clear the global context before and after each test.
 */
public class GlobalContextExtension implements BeforeEachCallback, AfterEachCallback {

    private static final ThreadLocal<Map<String, String>> savedSate = new ThreadLocal<>();

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        savedSate.set(GlobalContext.getContextMap());
        GlobalContext.getInstance().clear();
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        GlobalContext.getInstance().clear();
        Optional.ofNullable(savedSate.get()).ifPresent(map -> map.forEach(GlobalContext.getInstance()::add));
    }
}

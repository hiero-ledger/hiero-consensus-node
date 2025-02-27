// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static com.hedera.services.bdd.junit.extensions.ExtensionUtils.hapiTestMethodOf;
import static com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension.SHARED_NETWORK;
import static com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension.ensureEmbeddedNetwork;

import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

public class TestLifecycleExtension
        implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback, ParameterResolver {
    private static final String SPEC_MANAGER = "specManager";

    @Override
    public void beforeAll(@NonNull final ExtensionContext extensionContext) {
        if (isRootTestClass(extensionContext)) {
            ensureEmbeddedNetwork(extensionContext);
            getStore(extensionContext).put(SPEC_MANAGER, new TestLifecycle(SHARED_NETWORK.get()));
        }
        getStore(extensionContext)
                .get(SPEC_MANAGER, TestLifecycle.class)
                .setCurrentTestClass(extensionContext.getRequiredTestClass());
    }

    @Override
    public void afterAll(@NonNull final ExtensionContext extensionContext) {
        getStore(extensionContext)
                .get(SPEC_MANAGER, TestLifecycle.class)
                .restoreAnyOverriddenProperties(extensionContext.getRequiredTestClass());
        if (isRootTestClass(extensionContext)) {
            getStore(extensionContext).remove(SPEC_MANAGER);
        }
    }

    @Override
    public void beforeEach(@NonNull final ExtensionContext extensionContext) {
        hapiTestMethodOf(extensionContext)
                .ifPresent(ignore ->
                        HapiSpec.TEST_LIFECYCLE.set(getStore(extensionContext).get(SPEC_MANAGER, TestLifecycle.class)));
    }

    @Override
    public void afterEach(@NonNull final ExtensionContext extensionContext) {
        HapiSpec.TEST_LIFECYCLE.remove();
    }

    @Override
    public boolean supportsParameter(
            @NonNull final ParameterContext parameterContext, @NonNull final ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == TestLifecycle.class
                && (parameterContext.getDeclaringExecutable().isAnnotationPresent(BeforeAll.class)
                        || parameterContext.getDeclaringExecutable().isAnnotationPresent(AfterAll.class));
    }

    @Override
    public Object resolveParameter(
            @NonNull final ParameterContext parameterContext, @NonNull final ExtensionContext extensionContext) {
        return getStore(extensionContext).get(SPEC_MANAGER, TestLifecycle.class);
    }

    private ExtensionContext.Store getStore(@NonNull final ExtensionContext extensionContext) {
        return extensionContext.getStore(
                ExtensionContext.Namespace.create(getClass(), rootTestClassOf(extensionContext)));
    }

    private static boolean isRootTestClass(@NonNull final ExtensionContext extensionContext) {
        return extensionContext.getRequiredTestClass().equals(rootTestClassOf(extensionContext));
    }

    private static Class<?> rootTestClassOf(@NonNull final ExtensionContext extensionContext) {
        final var maybeParentTestClass = extensionContext
                .getParent()
                .flatMap(ExtensionContext::getTestClass)
                .orElse(null);
        if (maybeParentTestClass != null) {
            return rootTestClassOf(extensionContext.getParent().get());
        } else {
            return extensionContext.getRequiredTestClass();
        }
    }
}

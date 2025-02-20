// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static org.junit.platform.commons.support.AnnotationSupport.isAnnotated;

import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hedera.services.bdd.junit.GenesisHapiTest;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.restart.RestartHapiTest;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtensionContext;

public class ExtensionUtils {
    public static Optional<Method> hapiTestMethodOf(@NonNull final ExtensionContext extensionContext) {
        return extensionContext.getTestMethod().filter(ExtensionUtils::isHapiTest);
    }

    private static boolean isHapiTest(@NonNull final Method method) {
        return isAnnotated(method, HapiTest.class)
                || isAnnotated(method, LeakyHapiTest.class)
                || isAnnotated(method, GenesisHapiTest.class)
                || isAnnotated(method, RestartHapiTest.class)
                || isAnnotated(method, EmbeddedHapiTest.class)
                || isAnnotated(method, RepeatableHapiTest.class)
                || isAnnotated(method, LeakyEmbeddedHapiTest.class)
                || isAnnotated(method, LeakyRepeatableHapiTest.class);
    }
}

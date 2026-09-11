// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.annotation.Annotation;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Utility class for working with annotations in JUnit 5 tests.
 */
class AnnotationUtils {

    private AnnotationUtils() {}

    /**
     * Finds an annotation on the test method first, falling back to the test class if not found on the method.
     *
     * @param extensionContext the extension context of the test
     * @param annotationType the annotation type to search for
     * @param <A> the annotation type
     * @return an optional containing the annotation if found
     */
    @NonNull
    public static <A extends Annotation> Optional<A> findAnnotation(
            @NonNull final ExtensionContext extensionContext, @NonNull final Class<A> annotationType) {
        return AnnotationSupport.findAnnotation(extensionContext.getElement(), annotationType)
                .or(() -> AnnotationSupport.findAnnotation(extensionContext.getTestClass(), annotationType));
    }
}

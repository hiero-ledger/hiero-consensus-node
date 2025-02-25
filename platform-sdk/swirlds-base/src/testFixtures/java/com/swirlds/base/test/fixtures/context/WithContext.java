// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.fixtures.context;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Any test that is annotated by this annotation will have an empty global and thread local context that can be used.
 * The context will be cleared after the test is finished. The test is executed in isolation, so the context will not be
 * shared with other tests.
 *
 * @see WithGlobalContext
 * @see WithThreadLocalContext
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@WithGlobalContext
@WithThreadLocalContext
@Inherited
public @interface WithContext {}

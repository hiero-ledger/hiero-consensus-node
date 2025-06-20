// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.query.annotations;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Qualifies a {@link com.hedera.node.app.workflows.query.QueryWorkflow}
 * as being used to process node operator queries.
 */
@Target({METHOD, PARAMETER, TYPE})
@Retention(RUNTIME)
@Documented
public @interface OperatorQueries {}

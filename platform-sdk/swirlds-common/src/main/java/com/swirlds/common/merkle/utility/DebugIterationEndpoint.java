// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.utility;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * When iterating a merkle tree with {@link MerkleTreeVisualizer}, do not iterate below nodes whose type has been
 * annotated with this flag.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DebugIterationEndpoint {}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import java.util.function.Predicate;

/**
 * A functional interface representing a filter for objects of type {@code T}.
 *
 * @param <T> the type of objects to filter
 */
@FunctionalInterface
public interface OtterFilter<T> extends Predicate<T> {}

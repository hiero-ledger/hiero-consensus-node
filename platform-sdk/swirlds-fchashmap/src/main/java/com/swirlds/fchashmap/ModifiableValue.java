// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fchashmap;

/**
 * A value from an {@link FCHashMap} that is safe to modify.
 *
 * @param value
 * 		the value from the FCHashMap, or null if no value exists
 * @param original
 * 		the original value that was copied. Equal to value if there was no copying performed
 * @param <V>
 * 		the type of the value
 */
public record ModifiableValue<V>(V value, V original) {}

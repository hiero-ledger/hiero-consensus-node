// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fchashmap.internal;

/**
 * Represents future purging work after a certain map copy is deleted.
 *
 * @param key
 * 		the key that requires purging
 * @param mutation
 * 		the mutation that requires purging
 * @param <K>
 * 		the type of the key
 * @param <V>
 * 		the type of the value
 */
public record PurgingEvent<K, V>(K key, Mutation<V> mutation) {
    public String toString() {
        return "[key = " + key + ", version = " + mutation.getVersion() + "]";
    }
}

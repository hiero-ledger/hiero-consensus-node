// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.io.counting;

/**
 * An enum for the type of counter to use in a counting stream.
 */
public enum CounterType {
    /** A counter that is thread safe. */
    THREAD_SAFE,

    /** A counter that is fast, but not thread safe. */
    FAST
}

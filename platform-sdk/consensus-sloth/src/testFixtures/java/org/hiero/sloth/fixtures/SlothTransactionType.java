// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures;

/**
 * The types of transactions used in the Sloth framework.
 */
public enum SlothTransactionType {
    /** Empty transactions, used for warmup or background load. */
    EMPTY,

    /** Benchmark transactions containing a submission timestamp for latency measurement. */
    BENCHMARK
}

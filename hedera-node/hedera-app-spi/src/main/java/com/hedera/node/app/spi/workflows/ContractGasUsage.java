// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

/**
 * A caller-owned report of the gas billed by one contract dispatch.
 *
 * <p>The contract handler records the execution outcome before its stream builder can be cleared by
 * rollback. The report is independent of the configured record/block stream mode and survives a
 * reverted dispatch. A dispatch that never produces an EVM outcome reports zero gas.
 */
public final class ContractGasUsage {
    private long gasUsed;

    /** Returns the reported gas, or zero when the dispatch did not produce an EVM outcome. */
    public long gasUsed() {
        return gasUsed;
    }

    /** Records the billed gas from the contract execution outcome. */
    public void recordGasUsed(final long gasUsed) {
        if (gasUsed < 0) {
            throw new IllegalArgumentException("Negative billed contract gas");
        }
        this.gasUsed = gasUsed;
    }
}

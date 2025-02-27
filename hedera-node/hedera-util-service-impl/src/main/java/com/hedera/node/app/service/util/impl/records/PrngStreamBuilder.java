// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util.impl.records;

import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@code StreamBuilder} specialization for tracking the side effects of a
 * {@code ConsensusCreateTopic} transaction.
 */
public interface PrngStreamBuilder extends StreamBuilder {
    /**
     * Tracks the random number generated within the range provided in
     * {@link com.hedera.hapi.node.util.UtilPrngTransactionBody} if range is greater than 0.
     *
     * @param num the random number generated within range
     * @return this builder
     */
    @NonNull
    PrngStreamBuilder entropyNumber(int num);

    /**
     * Tracks the pseudorandom 384-bit string generated when no output range is provided or range of 0 is provided in
     * {@link com.hedera.hapi.node.util.UtilPrngTransactionBody}.
     *
     * @param prngBytes the pseudorandom 384-bit string
     * @return this builder
     */
    @NonNull
    PrngStreamBuilder entropyBytes(@NonNull Bytes prngBytes);
}

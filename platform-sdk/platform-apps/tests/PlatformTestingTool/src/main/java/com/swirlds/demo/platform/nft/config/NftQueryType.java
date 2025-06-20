// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.nft.config;

import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.demo.platform.PlatformTestingToolState;
import com.swirlds.demo.platform.nft.NftSimpleQuerier;
import java.util.function.BiConsumer;

/**
 * This enum describes the different types of NFT queries that can be performed.
 */
public enum NftQueryType {

    /**
     * Given an account, fetch the tokens owned by that account.
     */
    SIMPLE(NftSimpleQuerier::execute);

    private final BiConsumer<PlatformTestingToolState, SpeedometerMetric> executor;

    NftQueryType(final BiConsumer<PlatformTestingToolState, SpeedometerMetric> executor) {
        this.executor = executor;
    }

    /**
     * Execute a given query.
     *
     * @param state
     * 		the state against which the query should be executed
     * @param metric
     * 		performance data about the query should be written here.
     */
    public void execute(final PlatformTestingToolState state, final SpeedometerMetric metric) {
        executor.accept(state, metric);
    }
}

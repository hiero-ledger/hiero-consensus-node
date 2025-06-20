// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.nft.config;

/**
 * Config for an NFT test.
 */
public class NftConfig {

    private NftQueryConfig queryConfig;

    public NftQueryConfig getQueryConfig() {
        return queryConfig;
    }

    /**
     * Set the configuration for NFT queries.
     *
     * @param queryConfig
     * 		the query config object
     */
    public void setQueryConfig(final NftQueryConfig queryConfig) {
        this.queryConfig = queryConfig;
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.nft.config;

/**
 * Configuration for queries during an NFT test.
 */
public class NftQueryConfig {

    private NftQueryType queryType;
    private int qps;
    private int numberOfThreads = 1;
    private int pageSize = 5;

    /**
     * Get the type of query to perform.
     */
    public NftQueryType getQueryType() {
        return queryType;
    }

    /**
     * Set the type of query to perform.
     */
    public void setQueryType(final NftQueryType queryType) {
        this.queryType = queryType;
    }

    /**
     * Get the number of queries that should be executed per second.
     */
    public int getQps() {
        return qps;
    }

    /**
     * Set the number of queries that should be executed per second.
     */
    public void setQps(final int qps) {
        this.qps = qps;
    }

    /**
     * Get the number of threads used for queries.
     */
    public int getNumberOfThreads() {
        return numberOfThreads;
    }

    /**
     * Set the number of threads used for queries.
     */
    public void setNumberOfThreads(final int numberOfThreads) {
        this.numberOfThreads = numberOfThreads;
    }

    /**
     * Get the size of each page to query
     * @return
     */
    public int getPageSize() {
        return pageSize;
    }

    /**
     * Set the size of each page to query
     * @param pageSize
     */
    public void setPageSize(final int pageSize) {
        this.pageSize = pageSize;
    }
}

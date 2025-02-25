// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

/**
 * This class is responsible for configuring how does PTT send queries for querying a leaf in the latest signed state
 */
public class QueryConfig {
    /** defines how many queries should be sent in each second for querying a leaf in the latest signed state */
    private long queriesSentPerSec = -1;

    public long getQueriesSentPerSec() {
        return queriesSentPerSec;
    }

    public void setQueriesSentPerSec(final long queriesSentPerSec) {
        this.queriesSentPerSec = queriesSentPerSec;
    }
}

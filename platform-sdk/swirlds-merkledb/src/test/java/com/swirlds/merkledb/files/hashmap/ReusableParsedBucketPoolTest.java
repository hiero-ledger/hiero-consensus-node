// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files.hashmap;

final class ReusableParsedBucketPoolTest extends ReusableBucketPoolTestBase {

    @Override
    protected ReusableBucketPool createPool(final int size) {
        return new ReusableBucketPool(size, pool -> new ParsedBucket(pool));
    }
}

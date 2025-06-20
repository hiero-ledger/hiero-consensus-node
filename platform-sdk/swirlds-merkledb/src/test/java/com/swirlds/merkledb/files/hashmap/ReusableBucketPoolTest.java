// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files.hashmap;

final class ReusableBucketPoolTest extends ReusableBucketPoolTestBase {

    @Override
    protected ReusableBucketPool createPool(final int size) {
        return new ReusableBucketPool(size, pool -> new Bucket(pool));
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files.hashmap;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Serializer for writing buckets into a DataFile.
 */
public class BucketSerializer {

    /** Bucket pool used by this serializer */
    private final ReusableBucketPool reusableBucketPool;

    public BucketSerializer() {
        reusableBucketPool = new ReusableBucketPool(Bucket::new);
    }

    /**
     * Reusable bucket pool for this bucket serializer.
     *
     * @return This serializer's reusable bucket pool.
     */
    public ReusableBucketPool getBucketPool() {
        return reusableBucketPool;
    }

    public int getSerializedSize(final Bucket bucket) {
        return bucket.sizeInBytes();
    }

    public void serialize(@NonNull final Bucket bucket, @NonNull final WritableSequentialData out) {
        bucket.writeTo(out);
    }

    public Bucket deserialize(@NonNull final ReadableSequentialData in) {
        final Bucket bucket = reusableBucketPool.getBucket();
        bucket.readFrom(in);
        return bucket;
    }
}

/*
 * Copyright (C) 2021-2024 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
        reusableBucketPool = new ReusableBucketPool(ParsedBucket::new);
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

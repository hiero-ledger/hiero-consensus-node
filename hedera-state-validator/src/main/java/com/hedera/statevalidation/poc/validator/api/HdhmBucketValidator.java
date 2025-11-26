// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.validator.api;

import com.swirlds.merkledb.files.hashmap.ParsedBucket;
import edu.umd.cs.findbugs.annotations.NonNull;

public interface HdhmBucketValidator extends Validator {
    void processBucket(long bucketLocation, @NonNull ParsedBucket bucket);
}

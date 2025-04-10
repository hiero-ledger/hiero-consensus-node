// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.sysfiles.domain.throttling;

import com.hedera.hapi.node.base.HederaFunctionality;
import java.util.ArrayList;
import java.util.List;

public class ThrottleDefinitions {
    private List<ThrottleBucket<HederaFunctionality>> buckets = new ArrayList<>();

    public List<ThrottleBucket<HederaFunctionality>> getBuckets() {
        return buckets;
    }

    public void setBuckets(List<ThrottleBucket<HederaFunctionality>> buckets) {
        this.buckets = buckets;
    }

    public static ThrottleDefinitions fromProto(com.hedera.hapi.node.transaction.ThrottleDefinitions defs) {
        var pojo = new ThrottleDefinitions();
        pojo.buckets.addAll(defs.throttleBuckets().stream()
                .map(HapiThrottleUtils::hapiBucketFromProto)
                .toList());
        return pojo;
    }

    public com.hedera.hapi.node.transaction.ThrottleDefinitions toProto() {
        return com.hedera.hapi.node.transaction.ThrottleDefinitions.newBuilder()
                .throttleBuckets(buckets.stream()
                        .map(HapiThrottleUtils::hapiBucketToProto)
                        .toList())
                .build();
    }
}

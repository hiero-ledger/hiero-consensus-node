// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.sysfiles.domain.throttling;

import com.hederahashgraph.api.proto.java.HederaFunctionality;
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

    public static ThrottleDefinitions fromProto(com.hederahashgraph.api.proto.java.ThrottleDefinitions defs) {
        var pojo = new ThrottleDefinitions();
        pojo.buckets.addAll(defs.getThrottleBucketsList().stream()
                .map(HapiThrottleUtils::hapiBucketFromProto)
                .toList());
        return pojo;
    }

    public com.hederahashgraph.api.proto.java.ThrottleDefinitions toProto() {
        return com.hederahashgraph.api.proto.java.ThrottleDefinitions.newBuilder()
                .addAllThrottleBuckets(buckets.stream()
                        .map(HapiThrottleUtils::hapiBucketToProto)
                        .toList())
                .build();
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.sysfiles.serdes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hederahashgraph.api.proto.java.ThrottleDefinitions;
import edu.umd.cs.findbugs.annotations.Nullable;

public class ThrottlesJsonToGrpcBytes implements SysFileSerde<String> {
    private static final int MINIMUM_NETWORK_SIZE = 1;

    private int believedNetworkSize;
    private final ObjectMapper mapper = new ObjectMapper();

    public ThrottlesJsonToGrpcBytes() {
        this.believedNetworkSize = MINIMUM_NETWORK_SIZE;
    }

    public void setBelievedNetworkSize(int believedNetworkSize) {
        this.believedNetworkSize = believedNetworkSize;
    }

    public ThrottlesJsonToGrpcBytes(int believedNetworkSize) {
        this.believedNetworkSize = believedNetworkSize;
    }

    @Override
    public String fromRawFile(byte[] bytes) {
        try {
            var defs = ThrottleDefinitions.parseFrom(bytes);
            var pojo = com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ThrottleDefinitions.fromProto(defs);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(pojo);
        } catch (InvalidProtocolBufferException | JsonProcessingException e) {
            throw new IllegalArgumentException("Unusable raw throttle definitions!", e);
        }
    }

    @Override
    public byte[] toRawFile(String styledFile, @Nullable String interpolatedSrcDir) {
        return toPojo(styledFile).toProto().toByteArray();
    }

    @Override
    public byte[] toValidatedRawFile(String styledFile, @Nullable String interpolatedSrcDir) {
        var pojo = toPojo(styledFile);
        for (var bucket : pojo.getBuckets()) {
            bucket.asThrottleMapping(believedNetworkSize);
        }
        return pojo.toProto().toByteArray();
    }

    private com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ThrottleDefinitions toPojo(String styledFile) {
        try {
            return mapper.readValue(
                    styledFile, com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ThrottleDefinitions.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unusable styled throttle definitions", e);
        }
    }

    @Override
    public String preferredFileName() {
        return "throttles.json";
    }
}

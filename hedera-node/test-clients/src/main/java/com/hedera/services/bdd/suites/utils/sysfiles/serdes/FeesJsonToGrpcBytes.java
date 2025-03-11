// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.sysfiles.serdes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.node.app.hapi.utils.sysfiles.serdes.FeesJsonToProtoSerde;
import com.hedera.services.bdd.suites.utils.sysfiles.FeeSchedulesListEntry;
import com.hederahashgraph.api.proto.java.CurrentAndNextFeeSchedule;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

public class FeesJsonToGrpcBytes implements SysFileSerde<String> {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String fromRawFile(byte[] bytes) {
        try {
            var grpc = CurrentAndNextFeeSchedule.parseFrom(bytes);

            List<FeeSchedulesListEntry> feeSchedules = new ArrayList<>();
            feeSchedules.add(FeeSchedulesListEntry.asCurrentFeeSchedule(
                    FeeSchedulesListEntry.from(grpc.getCurrentFeeSchedule())));
            feeSchedules.add(
                    FeeSchedulesListEntry.asNextFeeSchedule(FeeSchedulesListEntry.from(grpc.getNextFeeSchedule())));

            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(feeSchedules);
        } catch (InvalidProtocolBufferException | JsonProcessingException e) {
            throw new IllegalArgumentException("Not a set of fee schedules!", e);
        }
    }

    @Override
    public byte[] toRawFile(String styledFile, @Nullable String interpolatedSrcDir) {
        try {
            return FeesJsonToProtoSerde.parseFeeScheduleFromJson(styledFile).toByteArray();
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a set of fee schedules!", e);
        }
    }

    @Override
    public String preferredFileName() {
        return "feeSchedules.json";
    }
}

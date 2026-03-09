// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.sysfiles.serdes;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import org.hiero.hapi.support.fees.FeeSchedule;

public class SimpleFeesJsonToGrpcBytes implements SysFileSerde<String> {

    @Override
    public String fromRawFile(byte[] bytes) {
        try {
            final var schedule = FeeSchedule.PROTOBUF.parse(Bytes.wrap(bytes));
            return FeeSchedule.JSON.toJSON(schedule);
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a simple fee schedule!", e);
        }
    }

    @Override
    public byte[] toRawFile(String styledFile, @Nullable String interpolatedSrcDir) {
        try {
            final var schedule = FeeSchedule.JSON.parse(Bytes.wrap(styledFile.getBytes(StandardCharsets.UTF_8)));
            return FeeSchedule.PROTOBUF.toBytes(schedule).toByteArray();
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a simple fee schedule!", e);
        }
    }

    @Override
    public String preferredFileName() {
        return "simpleFeesSchedules.json";
    }
}

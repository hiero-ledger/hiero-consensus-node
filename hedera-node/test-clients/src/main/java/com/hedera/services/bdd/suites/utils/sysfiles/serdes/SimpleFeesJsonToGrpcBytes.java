// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.sysfiles.serdes;

import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.parseSimpleFeesSchedules;

import com.hedera.pbj.runtime.ParseException;
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
        } catch (ParseException e) {
            throw new IllegalArgumentException("Unusable raw simple fees schedule!", e);
        }
    }

    @Override
    public byte[] toRawFile(String styledFile, @Nullable String interpolatedSrcDir) {
        final var schedule = parseSimpleFeesSchedules(styledFile.getBytes(StandardCharsets.UTF_8));
        return FeeSchedule.PROTOBUF.toBytes(schedule).toByteArray();
    }

    @Override
    public String preferredFileName() {
        return "simpleFeesSchedules.json";
    }
}

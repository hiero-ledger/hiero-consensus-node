// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.fees;

import com.google.protobuf.util.JsonFormat;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hederahashgraph.api.proto.java.CurrentAndNextFeeSchedule;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Parses PBJ-serialized fee schedule bytes into Google protobuf types.
 *
 * <p>The node stores fee schedules using PBJ serialization
 * ({@code CurrentAndNextFeeSchedule.PROTOBUF.toBytes()}), but the test client
 * infrastructure uses Google protobuf types ({@code com.hederahashgraph.api.proto.java}).
 * This class bridges the gap by parsing with PBJ and converting via JSON round-trip.
 */
public final class FeeScheduleConverter {
    private FeeScheduleConverter() {}

    /**
     * Parses fee schedule bytes into Google protobuf {@link CurrentAndNextFeeSchedule}.
     *
     * @param bytes the PBJ-serialized (or standard protobuf) fee schedule bytes
     * @return the Google protobuf representation
     */
    public static CurrentAndNextFeeSchedule parseFrom(@NonNull final byte[] bytes) {
        try {
            final var pbj = com.hedera.hapi.node.base.CurrentAndNextFeeSchedule.PROTOBUF.parse(Bytes.wrap(bytes));
            final var json = com.hedera.hapi.node.base.CurrentAndNextFeeSchedule.JSON.toJSON(pbj);
            final var builder = CurrentAndNextFeeSchedule.newBuilder();
            JsonFormat.parser().ignoringUnknownFields().merge(json, builder);
            return builder.build();
        } catch (final Exception e) {
            throw new IllegalArgumentException("Failed to parse fee schedule bytes", e);
        }
    }
}

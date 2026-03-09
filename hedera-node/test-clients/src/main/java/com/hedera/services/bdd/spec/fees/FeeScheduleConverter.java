// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.fees;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hederahashgraph.api.proto.java.CurrentAndNextFeeSchedule;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;

/**
 * Bidirectional converter between PBJ and Google protobuf fee schedule types.
 *
 * <p>The node stores fee schedules using PBJ serialization, but parts of the test
 * infrastructure use Google protobuf types. This class bridges the gap via JSON
 * round-trip in both directions. Handles both PBJ and Google protobuf wire formats
 * transparently.
 */
public final class FeeScheduleConverter {
    private FeeScheduleConverter() {}

    /**
     * Parses fee schedule bytes into Google protobuf types. Tries PBJ parsing first
     * (to handle enum values unknown to Google protobuf), falls back to direct Google
     * protobuf parsing if PBJ fails.
     *
     * @param bytes the fee schedule bytes in either PBJ or Google protobuf wire format
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
            // PBJ parsing failed — try Google protobuf directly
            try {
                return CurrentAndNextFeeSchedule.parseFrom(bytes);
            } catch (final InvalidProtocolBufferException ex) {
                throw new IllegalArgumentException("Failed to parse fee schedule bytes in either format", ex);
            }
        }
    }

    /**
     * Converts a Google protobuf {@link CurrentAndNextFeeSchedule} to PBJ-serialized bytes.
     *
     * @param grpc the Google protobuf fee schedule
     * @return PBJ-serialized bytes
     */
    public static byte[] toBytes(@NonNull final CurrentAndNextFeeSchedule grpc) {
        try {
            final var json = JsonFormat.printer().print(grpc);
            final var pbj = com.hedera.hapi.node.base.CurrentAndNextFeeSchedule.JSON.parse(
                    Bytes.wrap(json.getBytes(StandardCharsets.UTF_8)));
            return com.hedera.hapi.node.base.CurrentAndNextFeeSchedule.PROTOBUF
                    .toBytes(pbj)
                    .toByteArray();
        } catch (final Exception e) {
            throw new IllegalArgumentException("Failed to convert fee schedule to PBJ bytes", e);
        }
    }
}

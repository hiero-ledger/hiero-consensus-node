// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.fees;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hederahashgraph.api.proto.java.CurrentAndNextFeeSchedule;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.FeeSchedule;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.TimestampSeconds;
import com.hederahashgraph.api.proto.java.TransactionFeeSchedule;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Parses PBJ-serialized fee schedule bytes into Google protobuf types.
 *
 * <p>The node stores fee schedules using PBJ serialization
 * ({@code CurrentAndNextFeeSchedule.PROTOBUF.toBytes()}), but the test client
 * infrastructure uses Google protobuf types ({@code com.hederahashgraph.api.proto.java}).
 * This class bridges the gap by parsing with PBJ and converting field-by-field.
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
            return convertCurrentAndNext(pbj);
        } catch (final Exception e) {
            throw new IllegalArgumentException("Failed to parse fee schedule bytes", e);
        }
    }

    private static CurrentAndNextFeeSchedule convertCurrentAndNext(
            @NonNull final com.hedera.hapi.node.base.CurrentAndNextFeeSchedule pbj) {
        final var builder = CurrentAndNextFeeSchedule.newBuilder();
        if (pbj.currentFeeSchedule() != null) {
            builder.setCurrentFeeSchedule(convertFeeSchedule(pbj.currentFeeSchedule()));
        }
        if (pbj.nextFeeSchedule() != null) {
            builder.setNextFeeSchedule(convertFeeSchedule(pbj.nextFeeSchedule()));
        }
        return builder.build();
    }

    private static FeeSchedule convertFeeSchedule(@NonNull final com.hedera.hapi.node.base.FeeSchedule pbj) {
        final var builder = FeeSchedule.newBuilder();
        for (final var tfs : pbj.transactionFeeSchedule()) {
            builder.addTransactionFeeSchedule(convertTxnFeeSchedule(tfs));
        }
        if (pbj.expiryTime() != null) {
            builder.setExpiryTime(
                    TimestampSeconds.newBuilder().setSeconds(pbj.expiryTime().seconds()));
        }
        return builder.build();
    }

    private static TransactionFeeSchedule convertTxnFeeSchedule(
            @NonNull final com.hedera.hapi.node.base.TransactionFeeSchedule pbj) {
        final var builder = TransactionFeeSchedule.newBuilder();
        final var functionality = HederaFunctionality.forNumber(pbj.hederaFunctionalityProtoOrdinal());
        if (functionality != null) {
            builder.setHederaFunctionality(functionality);
        }
        if (pbj.feeData() != null) {
            builder.setFeeData(convertFeeData(pbj.feeData()));
        }
        for (final var fd : pbj.fees()) {
            builder.addFees(convertFeeData(fd));
        }
        return builder.build();
    }

    private static FeeData convertFeeData(@NonNull final com.hedera.hapi.node.base.FeeData pbj) {
        final var builder = FeeData.newBuilder();
        final var subType = SubType.forNumber(pbj.subTypeProtoOrdinal());
        if (subType != null) {
            builder.setSubType(subType);
        }
        if (pbj.nodedata() != null) {
            builder.setNodedata(convertFeeComponents(pbj.nodedata()));
        }
        if (pbj.networkdata() != null) {
            builder.setNetworkdata(convertFeeComponents(pbj.networkdata()));
        }
        if (pbj.servicedata() != null) {
            builder.setServicedata(convertFeeComponents(pbj.servicedata()));
        }
        return builder.build();
    }

    private static FeeComponents convertFeeComponents(@NonNull final com.hedera.hapi.node.base.FeeComponents pbj) {
        return FeeComponents.newBuilder()
                .setMin(pbj.min())
                .setMax(pbj.max())
                .setConstant(pbj.constant())
                .setBpt(pbj.bpt())
                .setVpt(pbj.vpt())
                .setRbh(pbj.rbh())
                .setSbh(pbj.sbh())
                .setGas(pbj.gas())
                .setTv(pbj.tv())
                .setBpr(pbj.bpr())
                .setSbpr(pbj.sbpr())
                .build();
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.CurrentAndNextFeeSchedule;
import com.hedera.hapi.node.base.FeeComponents;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.base.FeeSchedule;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TimestampSeconds;
import com.hedera.hapi.node.base.TransactionFeeSchedule;
import com.hedera.node.app.fees.congestion.CongestionMultipliers;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FeeManagerTest {

    @Mock
    private ExchangeRateManager exchangeRateManager;

    @Mock
    private CongestionMultipliers congestionMultipliers;

    private FeeManager subject;

    @BeforeEach
    void setUp() {
        subject = new FeeManager(exchangeRateManager, congestionMultipliers, Set.of(), Set.of());
    }

    @Test
    void updateParsesCurrentAndNextFeeSchedule() {
        final var feeComponents = feeComponents();
        final var feeData = FeeData.newBuilder()
                .networkdata(feeComponents)
                .nodedata(feeComponents)
                .servicedata(feeComponents)
                .subType(SubType.DEFAULT)
                .build();
        final var expiryTime = TimestampSeconds.newBuilder().seconds(9_999_999L).build();
        final var txFeeSchedule = TransactionFeeSchedule.newBuilder()
                .hederaFunctionality(CRYPTO_CREATE)
                .fees(List.of(feeData))
                .build();
        final var feeSchedule = FeeSchedule.newBuilder()
                .transactionFeeSchedule(List.of(txFeeSchedule))
                .expiryTime(expiryTime)
                .build();
        final var schedules = CurrentAndNextFeeSchedule.newBuilder()
                .currentFeeSchedule(feeSchedule)
                .nextFeeSchedule(feeSchedule)
                .build();
        final var bytes = CurrentAndNextFeeSchedule.PROTOBUF.toBytes(schedules);

        final var result = subject.update(bytes);

        assertEquals(SUCCESS, result);
        final var loadedFeeData = subject.getFeeData(CRYPTO_CREATE, Instant.ofEpochSecond(1L), SubType.DEFAULT);
        assertEquals(100L, loadedFeeData.networkdataOrThrow().min());
        assertEquals(50_000L, loadedFeeData.networkdataOrThrow().max());
        assertEquals(1L, loadedFeeData.networkdataOrThrow().bpt());
    }

    @Test
    void updateSimpleFeesParsesFeeSchedule() {
        final var validSchedule = org.hiero.hapi.support.fees.FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(
                        makeExtraDef(Extra.KEYS, 1),
                        makeExtraDef(Extra.STATE_BYTES, 1),
                        makeExtraDef(Extra.SIGNATURES, 1))
                .node(NodeFee.DEFAULT
                        .copyBuilder()
                        .baseFee(100)
                        .extras(makeExtraIncluded(Extra.SIGNATURES, 1))
                        .build())
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(1).build())
                .services(makeService(
                        "Crypto",
                        makeServiceFee(CRYPTO_CREATE, 100, makeExtraIncluded(Extra.KEYS, 1))))
                .build();
        final var bytes = org.hiero.hapi.support.fees.FeeSchedule.PROTOBUF.toBytes(validSchedule);

        final var result = subject.updateSimpleFees(bytes);

        assertEquals(SUCCESS, result);
    }

    private static @NonNull FeeComponents feeComponents() {
        return FeeComponents.newBuilder()
                .min(100L)
                .max(50_000L)
                .bpt(1L)
                .vpt(2L)
                .rbh(3L)
                .sbh(4L)
                .gas(5L)
                .tv(6L)
                .bpr(7L)
                .sbpr(8L)
                .build();
    }
}

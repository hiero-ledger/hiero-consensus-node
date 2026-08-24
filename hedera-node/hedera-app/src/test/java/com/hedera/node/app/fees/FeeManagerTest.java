// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FEE_SCHEDULE_FILE_PART_UPLOADED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.node.app.fees.congestion.CongestionMultipliers;
import java.time.Instant;
import java.util.Set;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;
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
    void updateSimpleFeesParsesFeeSchedule() {
        final var validSchedule = org.hiero.hapi.support.fees.FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(
                        makeExtraDef(Extra.KEYS, 1),
                        makeExtraDef(Extra.STATE_BYTES, 1),
                        makeExtraDef(Extra.SIGNATURES, 1),
                        makeExtraDef(Extra.GAS, 852))
                .node(NodeFee.DEFAULT
                        .copyBuilder()
                        .baseFee(100)
                        .extras(makeExtraIncluded(Extra.SIGNATURES, 1))
                        .build())
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(1).build())
                .services(makeService("Crypto", makeServiceFee(CRYPTO_CREATE, 100, makeExtraIncluded(Extra.KEYS, 1))))
                .build();
        final var bytes = org.hiero.hapi.support.fees.FeeSchedule.PROTOBUF.toBytes(validSchedule);

        final var result = subject.updateSimpleFees(bytes);

        assertEquals(SUCCESS, result);
    }

    @Test
    void getGasPriceInTinyCentsUsesSimpleFeesWhenGasExtraPresent() {
        final var simpleSchedule = org.hiero.hapi.support.fees.FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(makeExtraDef(Extra.GAS, 100_000L))
                .node(NodeFee.DEFAULT.copyBuilder().baseFee(0).build())
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(1).build())
                .services(makeService("Crypto", makeServiceFee(CRYPTO_CREATE, 0)))
                .build();
        subject.updateSimpleFees(org.hiero.hapi.support.fees.FeeSchedule.PROTOBUF.toBytes(simpleSchedule));

        assertEquals(100_000L, subject.getGasPriceInTinyCents(Instant.now()));
    }

    @Test
    void updateSimpleFeesRejectsScheduleWithoutGasExtra() {
        final var simpleSchedule = org.hiero.hapi.support.fees.FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(makeExtraDef(Extra.KEYS, 1_000L))
                .node(NodeFee.DEFAULT.copyBuilder().baseFee(0).build())
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(1).build())
                .services(makeService("Crypto", makeServiceFee(CRYPTO_CREATE, 0)))
                .build();

        final var result =
                subject.updateSimpleFees(org.hiero.hapi.support.fees.FeeSchedule.PROTOBUF.toBytes(simpleSchedule));

        assertEquals(FEE_SCHEDULE_FILE_PART_UPLOADED, result);
    }

    @Test
    void getGasPriceInTinyCentsThrowsWhenSimpleFeesNotLoaded() {
        assertThrows(IllegalStateException.class, () -> subject.getGasPriceInTinyCents(Instant.ofEpochSecond(1L)));
    }
}

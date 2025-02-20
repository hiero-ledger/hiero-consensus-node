// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import static com.hedera.node.app.hapi.fees.test.AdapterUtils.feeDataFrom;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.hapi.fees.pricing.ResourceProvider;
import com.hedera.node.app.hapi.fees.pricing.UsableResource;
import com.hedera.node.app.hapi.fees.test.IdUtils;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.fees.usage.token.meta.ExtantFeeScheduleContext;
import com.hedera.node.app.hapi.fees.usage.token.meta.FeeScheduleUpdateMeta;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenPauseMeta;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenUnfreezeMeta;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenUnpauseMeta;
import com.hedera.node.app.hapi.fees.usage.token.meta.TokenWipeMeta;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hederahashgraph.api.proto.java.CustomFee;
import com.hederahashgraph.api.proto.java.FixedFee;
import com.hederahashgraph.api.proto.java.FractionalFee;
import com.hederahashgraph.api.proto.java.RoyaltyFee;
import com.hederahashgraph.api.proto.java.SubType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TokenOpsUsageTest {
    private static final TokenOpsUsage subject = new TokenOpsUsage();

    @Test
    void knowsBytesNeededToReprCustomFeeSchedule() {
        final var expectedHbarFixed = FeeBuilder.LONG_SIZE + BASIC_ENTITY_ID_SIZE;
        final var expectedHtsFixed = FeeBuilder.LONG_SIZE + 2 * BASIC_ENTITY_ID_SIZE;
        final var expectedFractional = 4 * FeeBuilder.LONG_SIZE + BASIC_ENTITY_ID_SIZE;
        final var expectedRoyaltyNoFallback = 2 * FeeBuilder.LONG_SIZE + BASIC_ENTITY_ID_SIZE;
        final var expectedRoyaltyHtsFallback = 3 * FeeBuilder.LONG_SIZE + 2 * BASIC_ENTITY_ID_SIZE;
        final var expectedRoyaltyHbarFallback = 3 * FeeBuilder.LONG_SIZE + BASIC_ENTITY_ID_SIZE;

        final var perHbarFixedFee = subject.bytesNeededToRepr(1, 0, 0, 0, 0, 0);
        final var perHtsFixedFee = subject.bytesNeededToRepr(0, 1, 0, 0, 0, 0);
        final var perFracFee = subject.bytesNeededToRepr(0, 0, 1, 0, 0, 0);
        final var perRoyaltyNoFallbackFee = subject.bytesNeededToRepr(0, 0, 0, 1, 0, 0);
        final var perRoyaltyHtsFallbackFee = subject.bytesNeededToRepr(0, 0, 0, 0, 1, 0);
        final var perRoyaltyHbarFallbackFee = subject.bytesNeededToRepr(0, 0, 0, 0, 0, 1);
        final var oneOfEach = subject.bytesNeededToRepr(1, 1, 1, 1, 1, 1);

        assertEquals(expectedHbarFixed, perHbarFixedFee);
        assertEquals(expectedHtsFixed, perHtsFixedFee);
        assertEquals(expectedFractional, perFracFee);
        assertEquals(expectedRoyaltyNoFallback, perRoyaltyNoFallbackFee);
        assertEquals(expectedRoyaltyHtsFallback, perRoyaltyHtsFallbackFee);
        assertEquals(expectedRoyaltyHbarFallback, perRoyaltyHbarFallbackFee);
        assertEquals(
                expectedHbarFixed
                        + expectedHtsFixed
                        + expectedFractional
                        + expectedRoyaltyNoFallback
                        + expectedRoyaltyHtsFallback
                        + expectedRoyaltyHbarFallback,
                oneOfEach);
    }

    @Test
    void canCountFeeTypes() {
        final List<CustomFee> aSchedule = new ArrayList<>();
        aSchedule.add(CustomFee.newBuilder()
                .setFixedFee(FixedFee.getDefaultInstance())
                .build());
        aSchedule.add(CustomFee.newBuilder()
                .setFixedFee(FixedFee.newBuilder().setDenominatingTokenId(IdUtils.asToken("1.2.3")))
                .build());
        aSchedule.add(CustomFee.newBuilder()
                .setFixedFee(FixedFee.newBuilder().setDenominatingTokenId(IdUtils.asToken("1.2.3")))
                .build());
        aSchedule.add(CustomFee.newBuilder()
                .setFractionalFee(FractionalFee.getDefaultInstance())
                .build());
        aSchedule.add(CustomFee.newBuilder()
                .setFractionalFee(FractionalFee.getDefaultInstance())
                .build());
        aSchedule.add(CustomFee.newBuilder()
                .setFractionalFee(FractionalFee.getDefaultInstance())
                .build());
        aSchedule.add(CustomFee.newBuilder()
                .setRoyaltyFee(RoyaltyFee.getDefaultInstance())
                .build());
        aSchedule.add(CustomFee.newBuilder()
                .setRoyaltyFee(RoyaltyFee.newBuilder()
                        .setFallbackFee(FixedFee.newBuilder().build()))
                .build());
        aSchedule.add(CustomFee.newBuilder()
                .setRoyaltyFee(RoyaltyFee.newBuilder()
                        .setFallbackFee(FixedFee.newBuilder()
                                .setDenominatingTokenId(IdUtils.asToken("1.2.3"))
                                .build()))
                .build());

        final var expected = subject.bytesNeededToRepr(1, 2, 3, 1, 1, 1);

        final var actual = subject.bytesNeededToRepr(aSchedule);

        assertEquals(expected, actual);
    }

    @Test
    void accumulatesBptAndRbhAsExpected() {
        final var now = 1_234_567L;
        final var lifetime = 7776000L;
        final var expiry = now + lifetime;
        final var curSize = subject.bytesNeededToRepr(1, 0, 1, 1, 0, 1);
        final var newSize = subject.bytesNeededToRepr(2, 1, 0, 2, 1, 0);
        final var ctx = new ExtantFeeScheduleContext(expiry, curSize);
        final var opMeta = new FeeScheduleUpdateMeta(now, newSize);
        final var sigUsage = new SigUsage(1, 2, 3);
        final var baseMeta = new BaseTransactionMeta(50, 0);
        final var exp = new UsageAccumulator();
        exp.resetForTransaction(baseMeta, sigUsage);
        exp.addBpt(newSize + BASIC_ENTITY_ID_SIZE);
        exp.addRbs((newSize - curSize) * lifetime);

        final var ans = new UsageAccumulator();

        subject.feeScheduleUpdateUsage(sigUsage, baseMeta, opMeta, ctx, ans);

        assertEquals(feeDataFrom(exp), feeDataFrom(ans));
    }

    @Test
    void tokenWipeUsageAccumulatorWorks() {
        final var sigUsage = new SigUsage(1, 2, 3);
        final var baseMeta = new BaseTransactionMeta(0, 0);
        final var tokenWipeMeta = new TokenWipeMeta(1000, SubType.TOKEN_NON_FUNGIBLE_UNIQUE, 12345, 1);
        final var accumulator = new UsageAccumulator();

        subject.tokenWipeUsage(sigUsage, baseMeta, tokenWipeMeta, accumulator);

        assertEquals(1078, accumulator.get(ResourceProvider.NETWORK, UsableResource.BPT));
        assertEquals(1, accumulator.get(ResourceProvider.NETWORK, UsableResource.VPT));
        assertEquals(1078, accumulator.get(ResourceProvider.NODE, UsableResource.BPT));
        assertEquals(0, accumulator.get(ResourceProvider.SERVICE, UsableResource.BPR));
        assertEquals(3, accumulator.get(ResourceProvider.NODE, UsableResource.VPT));
    }

    @Test
    void tokenFreezeUsageAccumulatorWorks() {
        final var sigUsage = new SigUsage(1, 2, 1);
        final var baseMeta = new BaseTransactionMeta(0, 0);
        final var tokenUnfreezeMeta = new TokenUnfreezeMeta(256);
        final var accumulator = new UsageAccumulator();

        subject.tokenUnfreezeUsage(sigUsage, baseMeta, tokenUnfreezeMeta, accumulator);

        assertEquals(334, accumulator.get(ResourceProvider.NETWORK, UsableResource.BPT));
        assertEquals(1, accumulator.get(ResourceProvider.NETWORK, UsableResource.VPT));
        assertEquals(334, accumulator.get(ResourceProvider.NODE, UsableResource.BPT));
        assertEquals(0, accumulator.get(ResourceProvider.SERVICE, UsableResource.BPR));
        assertEquals(1, accumulator.get(ResourceProvider.NODE, UsableResource.VPT));
    }

    @Test
    void tokenPauseUsageAccumulatorWorks() {
        final var sigUsage = new SigUsage(1, 2, 1);
        final var baseMeta = new BaseTransactionMeta(0, 0);
        final var tokenPauseMeta = new TokenPauseMeta(BASIC_ENTITY_ID_SIZE);
        final var accumulator = new UsageAccumulator();

        subject.tokenPauseUsage(sigUsage, baseMeta, tokenPauseMeta, accumulator);

        assertEquals(102, accumulator.get(ResourceProvider.NETWORK, UsableResource.BPT));
        assertEquals(1, accumulator.get(ResourceProvider.NETWORK, UsableResource.VPT));
        assertEquals(102, accumulator.get(ResourceProvider.NODE, UsableResource.BPT));
        assertEquals(0, accumulator.get(ResourceProvider.SERVICE, UsableResource.BPR));
        assertEquals(1, accumulator.get(ResourceProvider.NODE, UsableResource.VPT));
    }

    @Test
    void tokenUnpauseUsageAccumulatorWorks() {
        final var sigUsage = new SigUsage(1, 2, 1);
        final var baseMeta = new BaseTransactionMeta(0, 0);
        final var tokenUnpauseMeta = new TokenUnpauseMeta(BASIC_ENTITY_ID_SIZE);
        final var accumulator = new UsageAccumulator();

        subject.tokenUnpauseUsage(sigUsage, baseMeta, tokenUnpauseMeta, accumulator);

        assertEquals(102, accumulator.get(ResourceProvider.NETWORK, UsableResource.BPT));
        assertEquals(1, accumulator.get(ResourceProvider.NETWORK, UsableResource.VPT));
        assertEquals(102, accumulator.get(ResourceProvider.NODE, UsableResource.BPT));
        assertEquals(0, accumulator.get(ResourceProvider.SERVICE, UsableResource.BPR));
        assertEquals(1, accumulator.get(ResourceProvider.NODE, UsableResource.VPT));
    }
}

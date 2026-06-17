// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.gas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.CanonicalDispatchPrices;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator.FeeCalculatorFunction;
import com.hedera.node.app.service.contract.impl.exec.gas.TinybarValues;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemContractGasCalculatorTest {
    @Mock
    private TinybarValues tinybarValues;

    @Mock
    private FeeCalculatorFunction feeCalculator;

    @Mock
    private CanonicalDispatchPrices dispatchPrices;

    private SystemContractGasCalculator subject;

    @BeforeEach
    void setUp() {
        subject = new SystemContractGasCalculator(tinybarValues, dispatchPrices, feeCalculator);
    }

    @Test
    void returnsMinimumGasCostForViews() {
        assertEquals(1198L, subject.viewGasRequirement());
    }

    @Test
    void computesCanonicalDispatchType() {
        given(dispatchPrices.canonicalPriceInTinycents(DispatchType.APPROVE)).willReturn(123L);
        assertEquals(123L, subject.canonicalPriceInTinycents(DispatchType.APPROVE));
    }

    @Test
    void computesCanonicalDispatch() {
        given(feeCalculator.computeFee(TransactionBody.DEFAULT, AccountID.DEFAULT, null))
                .willReturn(123L);
        assertEquals(123L, subject.feeCalculatorPriceInTinyBars(TransactionBody.DEFAULT, AccountID.DEFAULT));
    }

    @Test
    void computesGasCostInTinybars() {
        given(tinybarValues.childTransactionTinybarGasPrice()).willReturn(2L);
        assertEquals(6L, subject.gasCostInTinybars(3L));
    }

    @Test
    void delegatesTopLevelGasPrice() {
        given(tinybarValues.topLevelTinybarGasPriceFullPrecision()).willReturn(123L);
        assertEquals(123L, subject.topLevelGasPriceInTinyBars());
    }

    @Test
    void gasRequirementDelegatesToFourArgWithNullSignatureMap() {
        given(dispatchPrices.canonicalPriceInTinycents(DispatchType.ASSOCIATE)).willReturn(0L);
        given(feeCalculator.computeFee(TransactionBody.DEFAULT, AccountID.DEFAULT, null))
                .willReturn(0L);
        given(tinybarValues.asTinycents(0L)).willReturn(0L);
        given(tinybarValues.childTransactionTinycentGasPrice()).willReturn(1L);

        final var via3Arg = subject.gasRequirement(TransactionBody.DEFAULT, DispatchType.ASSOCIATE, AccountID.DEFAULT);
        final var via4Arg =
                subject.gasRequirement(TransactionBody.DEFAULT, DispatchType.ASSOCIATE, AccountID.DEFAULT, null);
        assertEquals(via3Arg, via4Arg);
    }

    @Test
    void gasRequirementWithTinycentsDelegatesToFourArgWithNullSignatureMap() {
        given(feeCalculator.computeFee(TransactionBody.DEFAULT, AccountID.DEFAULT, null))
                .willReturn(0L);
        given(tinybarValues.asTinycents(0L)).willReturn(0L);
        given(tinybarValues.childTransactionTinycentGasPrice()).willReturn(1L);

        final var via3Arg = subject.gasRequirementWithTinycents(TransactionBody.DEFAULT, AccountID.DEFAULT, 500L);
        final var via4Arg = subject.gasRequirementWithTinycents(TransactionBody.DEFAULT, AccountID.DEFAULT, 500L, null);
        assertEquals(via3Arg, via4Arg);
    }

    /**
     * Verifies that providing a non-null {@code signatureMap} override to
     * {@link SystemContractGasCalculator#gasRequirement} results in higher gas than passing
     * {@code null}, and that the extra gas is proportional to the number of signatures.
     *
     * <p>The {@link FeeCalculatorFunction} used here mirrors what
     * {@code DispatchHandleContext.dispatchComputeFees} does: it measures the serialized size
     * of the provided {@code SignatureMap} and incorporates that into the returned fee.
     * With a gas price of 1 tinycent-per-unit and a 1:1 tinybar→tinycent conversion the
     * fee formula is linear, so gas(3 sigs) == 3 × gas(1 sig) exactly.
     */
    @Test
    void signatureMapOverrideIncreasesGasProportionallyToSignatureCount() {
        // Build identical sig pairs — each contributes the same number of serialized bytes.
        final var sigPair = SignaturePair.newBuilder()
                .pubKeyPrefix(Bytes.wrap(new byte[6]))
                .ed25519(Bytes.wrap(new byte[64]))
                .build();
        final var sigMap1 = SignatureMap.newBuilder().sigPair(sigPair).build();
        final var sigMap3 =
                SignatureMap.newBuilder().sigPair(sigPair, sigPair, sigPair).build();

        // FeeCalculatorFunction that mirrors DispatchHandleContext: fee = serialized sigmap bytes.
        final var calcSubject = new SystemContractGasCalculator(
                tinybarValues,
                dispatchPrices,
                (body, payer, sigMap) -> sigMap == null ? 0L : SignatureMap.PROTOBUF.measureRecord(sigMap));

        // Canonical price 0 so the feeCalculator result always wins; 1:1 tinybar→tinycent.
        given(dispatchPrices.canonicalPriceInTinycents(DispatchType.ASSOCIATE)).willReturn(0L);
        given(tinybarValues.asTinycents(anyLong())).willAnswer(inv -> (long) inv.getArguments()[0]);
        // Gas price 1 tinycent/unit keeps arithmetic exact (FEE_SCHEDULE_UNITS_PER_TINYCENT divides evenly).
        given(tinybarValues.childTransactionTinycentGasPrice()).willReturn(1L);

        final var gasNoMap =
                calcSubject.gasRequirement(TransactionBody.DEFAULT, DispatchType.ASSOCIATE, AccountID.DEFAULT);
        final var gas1Sig =
                calcSubject.gasRequirement(TransactionBody.DEFAULT, DispatchType.ASSOCIATE, AccountID.DEFAULT, sigMap1);
        final var gas3Sigs =
                calcSubject.gasRequirement(TransactionBody.DEFAULT, DispatchType.ASSOCIATE, AccountID.DEFAULT, sigMap3);

        assertThat(gas1Sig).isGreaterThan(gasNoMap);
        assertThat(gas3Sigs).isGreaterThan(gas1Sig);
        // Three identical sig pairs → exactly 3× the serialized bytes → exactly 3× the gas overhead.
        assertThat(gas3Sigs).isEqualTo(3L * gas1Sig);
    }
}

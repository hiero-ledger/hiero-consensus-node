// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.node.app.spi.fees.util.FeeUtils.DEFAULT_SUBUNITS_PER_HBAR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.fees.context.SimpleFeeContextImpl;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.workflows.QueryContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SimpleFeeContextImplTest {
    @Mock
    private FeeContext feeContext;

    @Mock
    private QueryContext queryContext;

    @Test
    void delegatesHighVolumeThrottleUtilizationToFeeContext() {
        final var subject = new SimpleFeeContextImpl(feeContext, null);
        given(feeContext.getHighVolumeThrottleUtilization(CRYPTO_CREATE)).willReturn(6_789);

        assertEquals(6_789, subject.getHighVolumeThrottleUtilization(CRYPTO_CREATE));
        verify(feeContext).getHighVolumeThrottleUtilization(CRYPTO_CREATE);
    }

    @Test
    void delegatesSubunitsPerWholeUnitToFeeContext() {
        final var subject = new SimpleFeeContextImpl(feeContext, null);
        given(feeContext.subunitsPerWholeUnit()).willReturn(1_000_000L);

        assertEquals(1_000_000L, subject.subunitsPerWholeUnit(), "Should delegate to feeContext");
    }

    @Test
    void returnsDefaultSubunitsWhenFeeContextIsNull() {
        final var subject = new SimpleFeeContextImpl(null, queryContext);

        assertEquals(
                DEFAULT_SUBUNITS_PER_HBAR,
                subject.subunitsPerWholeUnit(),
                "Should return default when feeContext is null");
    }

    @Test
    void throwsForQueryContextWhenHighVolumeUtilizationRequested() {
        final var subject = new SimpleFeeContextImpl(null, queryContext);

        assertThrows(
                UnsupportedOperationException.class, () -> subject.getHighVolumeThrottleUtilization(CRYPTO_CREATE));
    }
}

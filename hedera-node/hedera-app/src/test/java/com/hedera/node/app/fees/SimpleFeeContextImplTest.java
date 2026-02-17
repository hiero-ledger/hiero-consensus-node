// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

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
    void throwsForQueryContextWhenHighVolumeUtilizationRequested() {
        final var subject = new SimpleFeeContextImpl(null, queryContext);

        assertThrows(
                UnsupportedOperationException.class, () -> subject.getHighVolumeThrottleUtilization(CRYPTO_CREATE));
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.mock;

import com.hedera.node.app.service.networkadmin.impl.handlers.NetworkUncheckedSubmitHandler;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NetworkUncheckedSubmitHandlerTest {
    @Mock(strictness = LENIENT)
    private PreHandleContext preHandleContext;

    @Mock(strictness = LENIENT)
    private HandleContext handleContext;

    private final NetworkUncheckedSubmitHandler subject = new NetworkUncheckedSubmitHandler();

    @Test
    void preHandleThrowsUnsupported() {
        assertThatThrownBy(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(NOT_SUPPORTED));
    }

    @Test
    void handleThrowsUnsupported() {
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(NOT_SUPPORTED));
    }

    @Test
    @DisplayName("testCalculateFees")
    void testCalculateFees() {
        FeeContext mockFeeContext = mock(FeeContext.class);
        assertThatNoException().isThrownBy(() -> subject.calculateFees(mockFeeContext));
        final var result = subject.calculateFees(mockFeeContext);
        assertThat(result).isEqualTo(Fees.FREE);
    }
}

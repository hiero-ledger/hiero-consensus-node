// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import org.junit.jupiter.api.Test;

class InsufficientBalanceExceptionTest {

    @Test
    void testConstructor() {
        // when
        final InsufficientBalanceException exception =
                new InsufficientBalanceException(ResponseCodeEnum.UNAUTHORIZED, 42L);

        // then
        assertThat(exception.responseCode()).isEqualTo(ResponseCodeEnum.UNAUTHORIZED);
        assertThat(exception.getEstimatedFee()).isEqualTo(42L);
        assertThat(exception.getMessage()).isEqualTo(ResponseCodeEnum.UNAUTHORIZED.protoName());
    }

    @SuppressWarnings({"ThrowableNotThrown", "ConstantConditions"})
    @Test
    void testConstructorWithIllegalParameters() {
        assertThatThrownBy(() -> new InsufficientBalanceException(null, 42L)).isInstanceOf(NullPointerException.class);
    }
}

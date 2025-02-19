/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.mock;

import com.hedera.node.app.service.token.impl.handlers.CryptoAddLiveHashHandler;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryptoAddLiveHashHandlerTest {
    @Mock(strictness = LENIENT)
    private PreHandleContext preHandleContext;

    @Mock(strictness = LENIENT)
    private HandleContext handleContext;

    @Mock
    private PureChecksContext pureChecksContext;

    private final CryptoAddLiveHashHandler subject = new CryptoAddLiveHashHandler();

    @Test
    void pureChecksDoesNothing() {
        // Verify no exception is thrown
        Assertions.assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
    }

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
    void calculateFeesFree() {
        final var feeCtx = mock(FeeContext.class);
        final var result = subject.calculateFees(feeCtx);
        assertThat(result).isEqualTo(Fees.FREE);
    }
}

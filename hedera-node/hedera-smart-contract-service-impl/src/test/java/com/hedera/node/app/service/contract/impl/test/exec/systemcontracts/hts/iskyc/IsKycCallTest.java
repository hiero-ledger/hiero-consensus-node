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

package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.iskyc;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.revertOutputFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.iskyc.IsKycCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.iskyc.IsKycTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IsKycCallTest extends CallTestBase {
    @Test
    void returnsIsKycForPresentToken() {
        final var subject =
                new IsKycCall(gasCalculator, mockEnhancement(), false, FUNGIBLE_TOKEN, FUNGIBLE_TOKEN_HEADLONG_ADDRESS);

        final MockedStatic<ConversionUtils> conversionUtilsMockStatic = mockStatic(ConversionUtils.class);
        conversionUtilsMockStatic
                .when(() -> ConversionUtils.accountNumberForEvmReference(any(), any()))
                .thenReturn(1L);

        final var result = subject.execute().fullResult().result();
        conversionUtilsMockStatic.close();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(IsKycTranslator.IS_KYC
                        .getOutputs()
                        .encode(Tuple.of(SUCCESS.protoOrdinal(), false))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsIsKycForMissingToken() {
        final var subject =
                new IsKycCall(gasCalculator, mockEnhancement(), false, null, FUNGIBLE_TOKEN_HEADLONG_ADDRESS);

        final MockedStatic<ConversionUtils> conversionUtilsMockStatic = mockStatic(ConversionUtils.class);
        conversionUtilsMockStatic
                .when(() -> ConversionUtils.accountNumberForEvmReference(any(), any()))
                .thenReturn(1L);

        final var result = subject.execute().fullResult().result();
        conversionUtilsMockStatic.close();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(IsKycTranslator.IS_KYC
                        .getOutputs()
                        .encode(Tuple.of(INVALID_TOKEN_ID.protoOrdinal(), false))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsIsFrozenForMissingAccount() {
        final var subject =
                new IsKycCall(gasCalculator, mockEnhancement(), false, FUNGIBLE_TOKEN, FUNGIBLE_TOKEN_HEADLONG_ADDRESS);

        final MockedStatic<ConversionUtils> conversionUtilsMockStatic = mockStatic(ConversionUtils.class);
        conversionUtilsMockStatic
                .when(() -> ConversionUtils.accountNumberForEvmReference(any(), any()))
                .thenReturn(-1L);

        final var result = subject.execute().fullResult().result();
        conversionUtilsMockStatic.close();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(IsKycTranslator.IS_KYC
                        .getOutputs()
                        .encode(Tuple.of(INVALID_ACCOUNT_ID.protoOrdinal(), false))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsIsKycForMissingTokenStaticCall() {
        final var subject =
                new IsKycCall(gasCalculator, mockEnhancement(), true, null, FUNGIBLE_TOKEN_HEADLONG_ADDRESS);

        final MockedStatic<ConversionUtils> conversionUtilsMockStatic = mockStatic(ConversionUtils.class);
        conversionUtilsMockStatic
                .when(() -> ConversionUtils.accountNumberForEvmReference(any(), any()))
                .thenReturn(1L);

        final var result = subject.execute().fullResult().result();
        conversionUtilsMockStatic.close();

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(revertOutputFor(INVALID_TOKEN_ID), result.getOutput());
    }

    @Test
    void returnsIsFrozenForMissingAccountStaticCall() {
        final var subject =
                new IsKycCall(gasCalculator, mockEnhancement(), true, FUNGIBLE_TOKEN, FUNGIBLE_TOKEN_HEADLONG_ADDRESS);

        final MockedStatic<ConversionUtils> conversionUtilsMockStatic = mockStatic(ConversionUtils.class);
        conversionUtilsMockStatic
                .when(() -> ConversionUtils.accountNumberForEvmReference(any(), any()))
                .thenReturn(-1L);

        final var result = subject.execute().fullResult().result();
        conversionUtilsMockStatic.close();

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(revertOutputFor(INVALID_ACCOUNT_ID), result.getOutput());
    }
}

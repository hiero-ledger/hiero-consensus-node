/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.transfer;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_167_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.swirlds.common.utility.CommonUtils;
import java.lang.reflect.Field;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClassicTransfersTranslatorTest extends CallTestBase {

    private static final String ABI_ID_TRANSFER_TOKEN = "eca36917";
    private static final String ABI_ID_CRYPTO_TRANSFER_V2 = "0e71804f";

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private VerificationStrategies verificationStrategies;

    @Mock
    private ClassicTransfersDecoder classicTransfersDecoder;

    @Mock
    private VerificationStrategy strategy;

    @Mock
    private ContractMetrics contractMetrics;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    private ClassicTransfersTranslator subject;

    private List<CallTranslator<HtsCallAttempt>> callTranslators;

    @BeforeEach
    void setUp() {
        callTranslators = List.of(
                new ClassicTransfersTranslator(classicTransfersDecoder, systemContractMethodRegistry, contractMetrics));
    }

    @Test
    void returnsAttemptWithAuthorizingId() throws NoSuchFieldException, IllegalAccessException {
        given(classicTransfersDecoder.checkForFailureStatus(any())).willReturn(null);
        given(addressIdConverter.convertSender(EIP_1014_ADDRESS)).willReturn(A_NEW_ACCOUNT_ID);
        given(addressIdConverter.convertSender(NON_SYSTEM_LONG_ZERO_ADDRESS)).willReturn(A_NEW_ACCOUNT_ID);
        given(verificationStrategies.activatingOnlyContractKeysFor(
                        NON_SYSTEM_LONG_ZERO_ADDRESS, true, nativeOperations))
                .willReturn(strategy);

        subject =
                new ClassicTransfersTranslator(classicTransfersDecoder, systemContractMethodRegistry, contractMetrics);
        final var call = subject.callFrom(givenV2SubjectWithV2Enabled(ABI_ID_TRANSFER_TOKEN));
        Field senderIdField = ClassicTransfersCall.class.getDeclaredField("senderId");
        senderIdField.setAccessible(true);
        AccountID senderID = (AccountID) senderIdField.get(call);
        assertEquals(A_NEW_ACCOUNT_ID, senderID);
    }

    @Test
    void returnsAttemptWithSenderId() throws NoSuchFieldException, IllegalAccessException {
        given(classicTransfersDecoder.checkForFailureStatus(any())).willReturn(null);
        given(addressIdConverter.convertSender(EIP_1014_ADDRESS)).willReturn(B_NEW_ACCOUNT_ID);
        given(verificationStrategies.activatingOnlyContractKeysFor(
                        NON_SYSTEM_LONG_ZERO_ADDRESS, true, nativeOperations))
                .willReturn(strategy);

        subject =
                new ClassicTransfersTranslator(classicTransfersDecoder, systemContractMethodRegistry, contractMetrics);
        final var call = subject.callFrom(givenV2SubjectWithV2Enabled(ABI_ID_CRYPTO_TRANSFER_V2));
        Field senderIdField = ClassicTransfersCall.class.getDeclaredField("senderId");
        senderIdField.setAccessible(true);
        AccountID senderID = (AccountID) senderIdField.get(call);
        assertEquals(B_NEW_ACCOUNT_ID, senderID);
    }

    private HtsCallAttempt givenV2SubjectWithV2Enabled(final String functionSelector) {
        final var input = Bytes.wrap(CommonUtils.unhex(functionSelector));

        return new HtsCallAttempt(
                HTS_167_CONTRACT_ID,
                input,
                EIP_1014_ADDRESS,
                NON_SYSTEM_LONG_ZERO_ADDRESS,
                true,
                mockEnhancement(),
                DEFAULT_CONFIG,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                callTranslators,
                systemContractMethodRegistry,
                false);
    }
}

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

package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.freeze;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.freeze.FreezeUnfreezeTranslator.FREEZE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.freeze.FreezeUnfreezeTranslator.UNFREEZE;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHtsAttemptWithSelector;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.freeze.FreezeUnfreezeDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.freeze.FreezeUnfreezeTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FreezeUnfreezeTranslatorTest {

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private SystemContractGasCalculator gasCalculator;

    @Mock
    private Enhancement enhancement;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private VerificationStrategies verificationStrategies;

    @Mock
    private ContractMetrics contractMetrics;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    private final FreezeUnfreezeDecoder decoder = new FreezeUnfreezeDecoder();
    private FreezeUnfreezeTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new FreezeUnfreezeTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void freezeMatches() {
        attempt = prepareHtsAttemptWithSelector(
                FREEZE,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void unfreezeMatches() {
        attempt = prepareHtsAttemptWithSelector(
                UNFREEZE,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void falseOnInvalidSelector() {
        attempt = prepareHtsAttemptWithSelector(
                BURN_TOKEN_V2,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }
}

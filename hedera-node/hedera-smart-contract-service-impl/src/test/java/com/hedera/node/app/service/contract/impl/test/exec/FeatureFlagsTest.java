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

package com.hedera.node.app.service.contract.impl.test.exec;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.service.contract.impl.exec.v046.Version046FeatureFlags;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FeatureFlagsTest {

    @Test
    void isAllowCallsToNonContractAccountsEnabledGrandfatherTest() {
        final var subject = new Version046FeatureFlags();
        final var config2 = HederaTestConfigBuilder.create()
                .withValue(
                        "contracts.evm.nonExtantContractsFail",
                        ConversionUtils.numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS))
                .getOrCreateConfig();

        final var contractsConfig = config2.getConfigData(ContractsConfig.class);
        assertTrue(subject.isAllowCallsToNonContractAccountsEnabled(contractsConfig, 1L));
        assertFalse(subject.isAllowCallsToNonContractAccountsEnabled(
                contractsConfig, ConversionUtils.numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS)));
    }
}

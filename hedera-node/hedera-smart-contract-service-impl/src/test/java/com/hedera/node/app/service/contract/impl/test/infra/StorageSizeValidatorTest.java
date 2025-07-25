// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.infra;

import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertExhaustsResourceLimit;

import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.infra.StorageSizeValidator;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StorageSizeValidatorTest {
    private static final long PRETEND_MAX_AGGREGATE = 123456L;

    @Mock
    private HederaOperations extWorldScope;

    private StorageSizeValidator subject;

    @Test
    void throwsOnTooManyAggregatePairs() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("contracts.maxKvPairs.aggregate", PRETEND_MAX_AGGREGATE)
                .getOrCreateConfig();

        subject = new StorageSizeValidator(config.getConfigData(ContractsConfig.class));

        assertExhaustsResourceLimit(
                () -> subject.assertValid(PRETEND_MAX_AGGREGATE + 1), MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED);
    }
}

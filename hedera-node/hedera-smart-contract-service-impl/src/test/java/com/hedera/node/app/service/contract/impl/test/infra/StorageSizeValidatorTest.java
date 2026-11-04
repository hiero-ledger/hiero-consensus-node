// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.infra;

import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_CONTRACT_STORAGE_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertExhaustsResourceLimit;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.infra.StorageSizeValidator;
import com.hedera.node.app.service.contract.impl.state.StorageSizeChange;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StorageSizeValidatorTest {
    private static final long PRETEND_MAX_AGGREGATE = 123456L;

    @Mock
    private HederaNativeOperations nativeOperations;

    private StorageSizeValidator subject;

    @Test
    void throwsOnTooManyAggregatePairs() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("contracts.maxKvPairs.aggregate", PRETEND_MAX_AGGREGATE)
                .getOrCreateConfig();

        subject = new StorageSizeValidator(config.getConfigData(ContractsConfig.class));

        assertExhaustsResourceLimit(
                () -> subject.assertValid(PRETEND_MAX_AGGREGATE + 1, nativeOperations, List.of()),
                MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED);
    }

    @Test
    void throwsOnTooManyIndividualPairs() {
        final var pretendMaxIndividual = 666L;
        final var underLimitNumber = ContractID.newBuilder().contractNum(123L).build();
        final var overLimitNumber = ContractID.newBuilder().contractNum(321L).build();
        final var config = HederaTestConfigBuilder.create()
                .withValue("contracts.maxKvPairs.individual", pretendMaxIndividual)
                .getOrCreateConfig();
        final var sizeChanges =
                List.of(new StorageSizeChange(underLimitNumber, 1, 2), new StorageSizeChange(overLimitNumber, 0, 1));
        given(nativeOperations.getAccount(underLimitNumber))
                .willReturn(Account.newBuilder()
                        .contractKvPairsNumber((int) pretendMaxIndividual - 1)
                        .build());
        given(nativeOperations.getAccount(overLimitNumber))
                .willReturn(Account.newBuilder()
                        .contractKvPairsNumber((int) pretendMaxIndividual)
                        .build());

        subject = new StorageSizeValidator(config.getConfigData(ContractsConfig.class));

        assertExhaustsResourceLimit(
                () -> subject.assertValid(PRETEND_MAX_AGGREGATE, nativeOperations, sizeChanges),
                MAX_CONTRACT_STORAGE_EXCEEDED);
    }

    @Test
    void validatesFinalUsageAfterRemovalAgainstCurrentAccountMetadata() {
        final var contractId = ContractID.newBuilder().contractNum(123L).build();
        final var config = HederaTestConfigBuilder.create()
                .withValue("contracts.maxKvPairs.individual", 1)
                .getOrCreateConfig();
        subject = new StorageSizeValidator(config.getConfigData(ContractsConfig.class));
        given(nativeOperations.getAccount(contractId))
                .willReturn(Account.newBuilder().contractKvPairsNumber(2).build());

        assertDoesNotThrow(
                () -> subject.assertValid(1L, nativeOperations, List.of(new StorageSizeChange(contractId, 1, 0))));
    }

    @Test
    void individualUsageCannotOverflowWhenAddingToTheCurrentCount() {
        final var contractId = ContractID.newBuilder().contractNum(123L).build();
        final var config = HederaTestConfigBuilder.create()
                .withValue("contracts.maxKvPairs.individual", Integer.MAX_VALUE)
                .getOrCreateConfig();
        subject = new StorageSizeValidator(config.getConfigData(ContractsConfig.class));
        given(nativeOperations.getAccount(contractId))
                .willReturn(Account.newBuilder()
                        .contractKvPairsNumber(Integer.MAX_VALUE)
                        .build());

        assertExhaustsResourceLimit(
                () -> subject.assertValid(1L, nativeOperations, List.of(new StorageSizeChange(contractId, 0, 1))),
                MAX_CONTRACT_STORAGE_EXCEEDED);
    }
}

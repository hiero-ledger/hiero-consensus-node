// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.isvalidalias;

import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.MISSING_ENTITY_NUMBER;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isvalidalias.IsValidAliasTranslator.IS_VALID_ALIAS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ALIASED_RECEIVER;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_HEDERA_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.RECEIVER_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.RECEIVER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.UNALIASED_RECEIVER;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asHeadlongAddress;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.realm;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.shard;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmAddress;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isvalidalias.IsValidAliasCall;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

public class IsValidAliasCallTest extends CallTestBase {

    @Mock
    private HasCallAttempt attempt;

    @Mock
    private com.hedera.hapi.node.state.token.Account account;

    private IsValidAliasCall subject;

    @Test
    void successfulCallWithEvmAddress() {

        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());

        // Arrange to use an account that has an alias
        given(nativeOperations.resolveAlias(
                        DEFAULT_HEDERA_CONFIG.shard(), DEFAULT_HEDERA_CONFIG.realm(), RECEIVER_ADDRESS))
                .willReturn(ALIASED_RECEIVER.accountId().accountNumOrThrow());
        given(nativeOperations.getAccount(any(AccountID.class))).willReturn(ALIASED_RECEIVER);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(nativeOperations.configuration()).willReturn(HederaTestConfigBuilder.createConfig());
        subject = new IsValidAliasCall(attempt, asHeadlongAddress(RECEIVER_ADDRESS.toByteArray()));
        final var result = subject.execute(frame).fullResult().result();

        assertEquals(State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(IS_VALID_ALIAS
                        .getOutputs()
                        .encode(Tuple.singleton(true))
                        .array()),
                result.getOutput());
    }

    @Test
    void successfulCallWithValidLongZeroWithAlias() {
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(nativeOperations.getAccount(any(AccountID.class))).willReturn(ALIASED_RECEIVER);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        subject = new IsValidAliasCall(
                attempt, asHeadlongAddress(asEvmAddress(shard, realm, RECEIVER_ID.accountNumOrThrow())));
        final var result = subject.execute(frame).fullResult().result();

        assertEquals(State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(IS_VALID_ALIAS
                        .getOutputs()
                        .encode(Tuple.singleton(true))
                        .array()),
                result.getOutput());
    }

    @Test
    void successfulCallWithValidLongZeroWithoutAlias() {
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(nativeOperations.getAccount(any(AccountID.class))).willReturn(UNALIASED_RECEIVER);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        subject = new IsValidAliasCall(
                attempt, asHeadlongAddress(asEvmAddress(shard, realm, RECEIVER_ID.accountNumOrThrow())));
        final var result = subject.execute(frame).fullResult().result();

        assertEquals(State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(IS_VALID_ALIAS
                        .getOutputs()
                        .encode(Tuple.singleton(true))
                        .array()),
                result.getOutput());
    }

    @Test
    void failsWhenNoAccountHasAlias() {
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());

        given(nativeOperations.resolveAlias(
                        DEFAULT_HEDERA_CONFIG.shard(), DEFAULT_HEDERA_CONFIG.realm(), RECEIVER_ADDRESS))
                .willReturn(ALIASED_RECEIVER.accountId().accountNumOrThrow());
        given(nativeOperations.getAccount(any(AccountID.class))).willReturn(null);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(nativeOperations.configuration()).willReturn(HederaTestConfigBuilder.createConfig());
        subject = new IsValidAliasCall(attempt, asHeadlongAddress(RECEIVER_ADDRESS.toByteArray()));
        final var result = subject.execute(frame).fullResult().result();

        assertEquals(State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(IS_VALID_ALIAS
                        .getOutputs()
                        .encode(Tuple.singleton(false))
                        .array()),
                result.getOutput());
    }

    @Test
    void failsWhenLongZeroAccountDoesNotExist() {
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(nativeOperations.configuration()).willReturn(HederaTestConfigBuilder.createConfig());
        given(nativeOperations.resolveAlias(
                        DEFAULT_HEDERA_CONFIG.shard(), DEFAULT_HEDERA_CONFIG.realm(), OWNER_ADDRESS))
                .willReturn(MISSING_ENTITY_NUMBER);

        subject = new IsValidAliasCall(attempt, asHeadlongAddress(OWNER_ADDRESS.toByteArray()));
        final var result = subject.execute(frame).fullResult().result();

        assertEquals(State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(IS_VALID_ALIAS
                        .getOutputs()
                        .encode(Tuple.singleton(false))
                        .array()),
                result.getOutput());
    }
}

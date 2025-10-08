// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.hederaaccountnumalias;

import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.MISSING_ENTITY_NUMBER;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hederaaccountnumalias.HederaAccountNumAliasTranslator.HEDERA_ACCOUNT_NUM_ALIAS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ALIASED_RECEIVER;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_HEDERA_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.RECEIVER_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.RECEIVER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asHeadlongAddress;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.utils.ConstantUtils.ZERO_ADDRESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hederaaccountnumalias.HederaAccountNumAliasCall;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

public class HederaAccountNumAliasCallTest extends CallTestBase {

    @Mock
    private HasCallAttempt attempt;

    @Mock
    private com.hedera.hapi.node.state.token.Account account;

    private HederaAccountNumAliasCall subject;

    @Test
    void successfulCall() {

        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());

        // Arrange to use an account that has an alias
        given(nativeOperations.resolveAlias(
                        DEFAULT_HEDERA_CONFIG.shard(), DEFAULT_HEDERA_CONFIG.realm(), RECEIVER_ADDRESS))
                .willReturn(ALIASED_RECEIVER.accountId().accountNumOrThrow());
        given(nativeOperations.getAccount(any(AccountID.class))).willReturn(ALIASED_RECEIVER);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(nativeOperations.configuration()).willReturn(DEFAULT_CONFIG);

        subject = new HederaAccountNumAliasCall(attempt, asHeadlongAddress(RECEIVER_ADDRESS.toByteArray()));
        final var result = subject.execute(frame).fullResult().result();

        assertEquals(State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(HEDERA_ACCOUNT_NUM_ALIAS
                        .getOutputs()
                        .encode(Tuple.of(
                                (long) ResponseCodeEnum.SUCCESS.protoOrdinal(),
                                asHeadlongAddress(RECEIVER_ID.accountNumOrThrow())))
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
        given(nativeOperations.configuration()).willReturn(DEFAULT_CONFIG);
        subject = new HederaAccountNumAliasCall(attempt, asHeadlongAddress(RECEIVER_ADDRESS.toByteArray()));
        final var result = subject.execute(frame).fullResult().result();

        assertEquals(State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(HEDERA_ACCOUNT_NUM_ALIAS
                        .getOutputs()
                        .encode(Tuple.of((long) ResponseCodeEnum.INVALID_SOLIDITY_ADDRESS.protoOrdinal(), ZERO_ADDRESS))
                        .array()),
                result.getOutput());
    }

    @Test
    void failsWhenAliasMapsToAccountButAccountDoesNotExistSomehow() {
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());

        given(nativeOperations.resolveAlias(
                        DEFAULT_HEDERA_CONFIG.shard(), DEFAULT_HEDERA_CONFIG.realm(), RECEIVER_ADDRESS))
                .willReturn(MISSING_ENTITY_NUMBER);
        given(nativeOperations.configuration()).willReturn(DEFAULT_CONFIG);
        subject = new HederaAccountNumAliasCall(attempt, asHeadlongAddress(RECEIVER_ADDRESS.toByteArray()));
        final var result = subject.execute(frame).fullResult().result();

        assertEquals(State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(HEDERA_ACCOUNT_NUM_ALIAS
                        .getOutputs()
                        .encode(Tuple.of((long) ResponseCodeEnum.INVALID_SOLIDITY_ADDRESS.protoOrdinal(), ZERO_ADDRESS))
                        .array()),
                result.getOutput());
    }
}

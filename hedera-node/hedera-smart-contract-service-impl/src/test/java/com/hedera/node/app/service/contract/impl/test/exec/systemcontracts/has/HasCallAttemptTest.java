// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract.HAS_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_BUT_IS_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asHeadlongAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarapprove.HbarApproveCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarapprove.HbarApproveTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import java.math.BigInteger;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class HasCallAttemptTest extends CallTestBase {
    @Mock
    private VerificationStrategies verificationStrategies;

    @Mock
    private SignatureVerifier signatureVerifier;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private ContractMetrics contractMetrics;

    private List<CallTranslator<HasCallAttempt>> callTranslators;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    @BeforeEach
    void setUp() {
        callTranslators = List.of(
                new HbarAllowanceTranslator(systemContractMethodRegistry, contractMetrics),
                new HbarApproveTranslator(systemContractMethodRegistry, contractMetrics));
    }

    @Test
    void returnNullAccountIfAccountNotFound() {
        given(nativeOperations.getAccount(numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS)))
                .willReturn(null);
        final var input = TestHelpers.bytesForRedirectAccount(
                HbarAllowanceTranslator.HBAR_ALLOWANCE_PROXY.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject = new HasCallAttempt(
                HAS_CONTRACT_ID,
                input,
                EIP_1014_ADDRESS,
                false,
                mockEnhancement(),
                DEFAULT_CONFIG,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                callTranslators,
                systemContractMethodRegistry,
                false);
        assertNull(subject.redirectAccount());
    }

    @Test
    void invalidSelectorLeadsToMissingCall() {
        final var input = TestHelpers.bytesForRedirectAccount(new byte[4], NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject = new HasCallAttempt(
                HAS_CONTRACT_ID,
                input,
                EIP_1014_ADDRESS,
                false,
                mockEnhancement(),
                DEFAULT_CONFIG,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                callTranslators,
                systemContractMethodRegistry,
                false);
        assertNull(subject.asExecutableCall());
    }

    @Test
    void constructsHbarAllowanceProxy() {
        given(addressIdConverter.convert(asHeadlongAddress(NON_SYSTEM_BUT_IS_LONG_ZERO_ADDRESS)))
                .willReturn(A_NEW_ACCOUNT_ID);
        final var input = TestHelpers.bytesForRedirectAccount(
                HbarAllowanceTranslator.HBAR_ALLOWANCE_PROXY
                        .encodeCallWithArgs(asHeadlongAddress(NON_SYSTEM_BUT_IS_LONG_ZERO_ADDRESS))
                        .array(),
                NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject = new HasCallAttempt(
                HAS_CONTRACT_ID,
                input,
                EIP_1014_ADDRESS,
                false,
                mockEnhancement(),
                DEFAULT_CONFIG,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                callTranslators,
                systemContractMethodRegistry,
                false);
        assertInstanceOf(HbarAllowanceCall.class, subject.asExecutableCall());
    }

    @Test
    void constructsHbarAllowanceDirect() {
        given(addressIdConverter.convert(asHeadlongAddress(NON_SYSTEM_BUT_IS_LONG_ZERO_ADDRESS)))
                .willReturn(A_NEW_ACCOUNT_ID);
        given(addressIdConverter.convert(asHeadlongAddress(NON_SYSTEM_LONG_ZERO_ADDRESS)))
                .willReturn(B_NEW_ACCOUNT_ID);
        final var input = Bytes.wrap(HbarAllowanceTranslator.HBAR_ALLOWANCE
                .encodeCallWithArgs(
                        asHeadlongAddress(NON_SYSTEM_BUT_IS_LONG_ZERO_ADDRESS),
                        asHeadlongAddress(NON_SYSTEM_LONG_ZERO_ADDRESS))
                .array());
        final var subject = new HasCallAttempt(
                HAS_CONTRACT_ID,
                input,
                EIP_1014_ADDRESS,
                false,
                mockEnhancement(),
                DEFAULT_CONFIG,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                callTranslators,
                systemContractMethodRegistry,
                false);
        assertInstanceOf(HbarAllowanceCall.class, subject.asExecutableCall());
    }

    @Test
    void constructsHbarApproveProxy() {
        given(addressIdConverter.convert(asHeadlongAddress(NON_SYSTEM_BUT_IS_LONG_ZERO_ADDRESS)))
                .willReturn(A_NEW_ACCOUNT_ID);
        given(addressIdConverter.convertSender(EIP_1014_ADDRESS)).willReturn(B_NEW_ACCOUNT_ID);
        final var input = TestHelpers.bytesForRedirectAccount(
                HbarApproveTranslator.HBAR_APPROVE_PROXY
                        .encodeCallWithArgs(
                                asHeadlongAddress(NON_SYSTEM_BUT_IS_LONG_ZERO_ADDRESS), BigInteger.valueOf(10))
                        .array(),
                NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject = new HasCallAttempt(
                HAS_CONTRACT_ID,
                input,
                EIP_1014_ADDRESS,
                false,
                mockEnhancement(),
                DEFAULT_CONFIG,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                callTranslators,
                systemContractMethodRegistry,
                false);
        assertInstanceOf(HbarApproveCall.class, subject.asExecutableCall());
    }

    @Test
    void constructsHbarApproveDirect() {
        given(addressIdConverter.convert(asHeadlongAddress(NON_SYSTEM_BUT_IS_LONG_ZERO_ADDRESS)))
                .willReturn(A_NEW_ACCOUNT_ID);
        given(addressIdConverter.convert(asHeadlongAddress(NON_SYSTEM_LONG_ZERO_ADDRESS)))
                .willReturn(B_NEW_ACCOUNT_ID);
        final var input = Bytes.wrap(HbarApproveTranslator.HBAR_APPROVE
                .encodeCallWithArgs(
                        asHeadlongAddress(NON_SYSTEM_BUT_IS_LONG_ZERO_ADDRESS),
                        asHeadlongAddress(NON_SYSTEM_LONG_ZERO_ADDRESS),
                        BigInteger.valueOf(10))
                .array());
        final var subject = new HasCallAttempt(
                HAS_CONTRACT_ID,
                input,
                EIP_1014_ADDRESS,
                false,
                mockEnhancement(),
                DEFAULT_CONFIG,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                callTranslators,
                systemContractMethodRegistry,
                false);
        assertInstanceOf(HbarApproveCall.class, subject.asExecutableCall());
    }
}

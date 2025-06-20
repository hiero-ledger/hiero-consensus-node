// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_BUT_IS_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_BESU_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asHeadlongAddress;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarapprove.HbarApproveCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarapprove.HbarApproveTranslator;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import java.math.BigInteger;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class HasCallAttemptTest extends CallAttemptTestBase {

    @Mock
    private ContractMetrics contractMetrics;

    private List<CallTranslator<HasCallAttempt>> callTranslators;

    @BeforeEach
    void setUp() {
        callTranslators = List.of(
                new HbarAllowanceTranslator(systemContractMethodRegistry, contractMetrics),
                new HbarApproveTranslator(systemContractMethodRegistry, contractMetrics));
    }

    @Test
    void returnNullAccountIfAccountNotFound() {
        given(nativeOperations.getAccount(any(AccountID.class))).willReturn(null);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var input = TestHelpers.bytesForRedirectAccount(
                HbarAllowanceTranslator.HBAR_ALLOWANCE_PROXY.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject = createHasCallAttempt(input, callTranslators);
        assertNull(subject.redirectAccount());
    }

    @Test
    void invalidSelectorLeadsToMissingCall() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var input = TestHelpers.bytesForRedirectAccount(new byte[4], NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject = createHasCallAttempt(input, callTranslators);
        assertNull(subject.asExecutableCall());
    }

    @Test
    void constructsHbarAllowanceProxy() {
        given(addressIdConverter.convert(asHeadlongAddress(NON_SYSTEM_BUT_IS_LONG_ZERO_ADDRESS)))
                .willReturn(A_NEW_ACCOUNT_ID);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var input = TestHelpers.bytesForRedirectAccount(
                HbarAllowanceTranslator.HBAR_ALLOWANCE_PROXY
                        .encodeCallWithArgs(asHeadlongAddress(NON_SYSTEM_BUT_IS_LONG_ZERO_ADDRESS))
                        .array(),
                NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject = createHasCallAttempt(input, callTranslators);
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
        final var subject = createHasCallAttempt(input, callTranslators);
        assertInstanceOf(HbarAllowanceCall.class, subject.asExecutableCall());
    }

    @Test
    void constructsHbarApproveProxy() {
        given(addressIdConverter.convert(asHeadlongAddress(NON_SYSTEM_BUT_IS_LONG_ZERO_ADDRESS)))
                .willReturn(A_NEW_ACCOUNT_ID);
        given(addressIdConverter.convertSender(OWNER_BESU_ADDRESS)).willReturn(B_NEW_ACCOUNT_ID);
        final var input = TestHelpers.bytesForRedirectAccount(
                HbarApproveTranslator.HBAR_APPROVE_PROXY
                        .encodeCallWithArgs(
                                asHeadlongAddress(NON_SYSTEM_BUT_IS_LONG_ZERO_ADDRESS), BigInteger.valueOf(10))
                        .array(),
                NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var subject = createHasCallAttempt(input, callTranslators);
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
        final var subject = createHasCallAttempt(input, callTranslators);
        assertInstanceOf(HbarApproveCall.class, subject.asExecutableCall());
    }
}

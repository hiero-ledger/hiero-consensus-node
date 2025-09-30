// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.state;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceTranslator.HBAR_ALLOWANCE_PROXY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CODE_FACTORY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniBytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.state.EvmFrameState;
import com.hedera.node.app.service.contract.impl.state.ScheduleEvmAccount;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScheduleEvmAccountTest {
    private static final Address SCHEDULE_ADDRESS = Address.fromHexString("0000000000000000000000000000ffffffffffff");
    private static final Bytes SOME_PRETEND_CODE = Bytes.wrap("<NOT-REALLY-CODE>");
    private static final Hash SOME_PRETEND_CODE_HASH =
            Hash.wrap(Bytes32.wrap("<NOT-REALLY-BYTECODE-HASH-12345>".getBytes()));
    private static final String SIGN_SCHEDULE_FUNCTION_SELECTOR = "06d15889";

    @Mock
    private EvmFrameState state;

    private ScheduleEvmAccount subject;

    @BeforeEach
    void setUp() {
        subject = new ScheduleEvmAccount(SCHEDULE_ADDRESS, state);
    }

    @Test
    void scheduleFacade() {
        assertTrue(subject.isScheduleTxnFacade());
    }

    @Test
    void notTokenFacade() {
        assertFalse(subject.isTokenFacade());
    }

    @Test
    void notRegularAccountFacade() {
        assertFalse(subject.isRegularAccount());
    }

    @Test
    void doesntSupportGettingId() {
        assertThrows(IllegalStateException.class, subject::hederaId);
    }

    @Test
    void doesSupportGettingContractId() {
        given(state.entityIdFactory()).willReturn(entityIdFactory);
        final var tokenNum = ConversionUtils.numberOfLongZero(SCHEDULE_ADDRESS);
        assertEquals(ContractID.newBuilder().contractNum(tokenNum).build(), subject.hederaContractId());
    }

    @Test
    void usesGivenAddress() {
        assertSame(SCHEDULE_ADDRESS, subject.getAddress());
    }

    @Test
    void usesCodeFromState() {
        final var code = pbjToTuweniBytes(SOME_PRETEND_CODE);

        given(state.getScheduleRedirectCode(SCHEDULE_ADDRESS)).willReturn(code);

        assertSame(code, subject.getCode());
    }

    @Test
    void returnsEvmCode() {
        final var code = pbjToTuweniBytes(SOME_PRETEND_CODE);
        given(state.getScheduleRedirectCode(SCHEDULE_ADDRESS)).willReturn(code);
        assertEquals(
                CODE_FACTORY.createCode(code, false),
                subject.getEvmCode(
                        org.apache.tuweni.bytes.Bytes.fromHexString(SIGN_SCHEDULE_FUNCTION_SELECTOR), CODE_FACTORY));
    }

    @Test
    void usesHashFromState() {
        given(state.getScheduleRedirectCodeHash(SCHEDULE_ADDRESS)).willReturn(SOME_PRETEND_CODE_HASH);

        assertSame(SOME_PRETEND_CODE_HASH, subject.getCodeHash());
    }

    @Test
    void usesFixedNonce() {
        assertEquals(-1, subject.getNonce());
    }

    @Test
    void alwaysHasZeroWei() {
        assertSame(Wei.ZERO, subject.getBalance());
    }

    @Test
    void storageValuesAreAlwaysZero() {
        assertSame(UInt256.ZERO, subject.getStorageValue(UInt256.ONE));
        assertSame(UInt256.ZERO, subject.getOriginalStorageValue(UInt256.ONE));
    }

    @Test
    void neverEmpty() {
        assertFalse(subject.isEmpty());
    }

    @Test
    void doesNotSupportMutators() {
        final var code = pbjToTuweniBytes(SOME_PRETEND_CODE);
        assertThrows(UnsupportedOperationException.class, () -> subject.setNonce(1234));
        assertThrows(UnsupportedOperationException.class, () -> subject.setCode(code));
        assertThrows(UnsupportedOperationException.class, () -> subject.setStorageValue(UInt256.ONE, UInt256.ONE));
        assertThrows(UnsupportedOperationException.class, subject::clearStorage);
        assertThrows(UnsupportedOperationException.class, subject::getUpdatedStorage);
    }

    @Test
    void neverRegularAccount() {
        assertFalse(subject.isRegularAccount());
    }

    @Test
    void returnEvmCodeWhenCalledWithExpectedFunctionSelectorBytes() {
        final var code = pbjToTuweniBytes(SOME_PRETEND_CODE);
        given(state.getScheduleRedirectCode(SCHEDULE_ADDRESS)).willReturn(code);
        assertEquals(
                CODE_FACTORY.createCode(code, false),
                subject.getEvmCode(
                        org.apache.tuweni.bytes.Bytes.fromHexString(SIGN_SCHEDULE_FUNCTION_SELECTOR), CODE_FACTORY));
    }

    @Test
    void returnEmptyEvmCodeWhenCalledWithUnexpectedFunctionSelectorBytes() {
        assertEquals(
                CODE_FACTORY.createCode(org.apache.tuweni.bytes.Bytes.EMPTY, false),
                subject.getEvmCode(org.apache.tuweni.bytes.Bytes.wrap(HBAR_ALLOWANCE_PROXY.selector()), CODE_FACTORY));
    }
}

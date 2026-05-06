// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.state;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniBytes;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.state.EvmFrameState;
import com.hedera.node.app.service.contract.impl.state.TokenEvmAccount;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.worldstate.CodeDelegationHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenEvmAccountTest {
    private static final Address TOKEN_ADDRESS = Address.fromHexString("0000000000000000000000000000ffffffffffff");
    private static final Bytes SOME_PRETEND_CODE = Bytes.wrap("<NOT-REALLY-CODE>");

    @Mock
    private EvmFrameState state;

    private TokenEvmAccount subject;

    @BeforeEach
    void setUp() {
        subject = new TokenEvmAccount(TOKEN_ADDRESS, state);
    }

    @Test
    void tokenFacade() {
        assertTrue(subject.isTokenFacade());
    }

    @Test
    void notScheduleTxnFacade() {
        assertFalse(subject.isScheduleTxnFacade());
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
        final var tokenNum = ConversionUtils.numberOfLongZero(TOKEN_ADDRESS);
        assertEquals(ContractID.newBuilder().contractNum(tokenNum).build(), subject.hederaContractId());
    }

    @Test
    void usesGivenAddress() {
        assertSame(TOKEN_ADDRESS, subject.getAddress());
    }

    @Test
    void returnsEip7702DelegationIndicatorCode() {
        assertTrue(CodeDelegationHelper.hasCodeDelegation(subject.getCode()));
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
}

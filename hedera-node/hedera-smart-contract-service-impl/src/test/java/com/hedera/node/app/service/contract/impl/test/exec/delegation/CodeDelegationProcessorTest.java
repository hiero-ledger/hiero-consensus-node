// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.delegation;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static org.hyperledger.besu.evm.worldstate.CodeDelegationHelper.CODE_DELEGATION_PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.node.app.hapi.utils.ethereum.CodeDelegation;
import com.hedera.node.app.hapi.utils.ethereum.EthTxSigs;
import com.hedera.node.app.service.contract.impl.exec.delegation.CodeDelegationProcessor;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction;
import com.hedera.node.app.service.contract.impl.state.HederaEvmAccount;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.token.records.CryptoCreateStreamBuilder;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CodeDelegationProcessorTest {

    @Mock
    private WorldUpdater world;

    @Mock
    private HederaEvmTransaction tx;

    @Mock
    private CodeDelegation del;

    @Mock
    private ProxyWorldUpdater proxyWorldUpdater;

    @Mock
    private CryptoCreateStreamBuilder cryptoCreateStreamBuilder;

    private static final long CHAIN_ID = 298L;
    private static final BigInteger HALF_ORDER =
            new BigInteger("7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0", 16);

    private static Optional<EthTxSigs> mockAuthorityWithAddress(final Address addr) {
        return Optional.of(mock(EthTxSigs.class, invocation -> {
            if ("address".equals(invocation.getMethod().getName())) {
                return addr.toArrayUnsafe();
            } else if ("publicKey".equals(invocation.getMethod().getName())) {
                return new byte[32];
            }
            return null; // unused
        }));
    }

    @Test
    void processReturnsWhenNoDelegations() {
        when(tx.codeDelegations()).thenReturn(null);

        final var p = new CodeDelegationProcessor(CHAIN_ID);
        final var result = p.process(world, tx.gasLimit(), tx.codeDelegations());

        assertNotNull(result);
        verifyNoInteractions(world);
    }

    @Test
    void skipsWhenChainIdMismatch() {
        when(tx.codeDelegations()).thenReturn(List.of(del));
        when(del.getChainId()).thenReturn(CHAIN_ID + 1);
        when(world.updater()).thenReturn(proxyWorldUpdater);

        final var p = new CodeDelegationProcessor(CHAIN_ID);
        final var result = p.process(world, tx.gasLimit(), tx.codeDelegations());

        assertNotNull(result);
        verify(proxyWorldUpdater).commit();
    }

    @Test
    void skipsWhenNonceIsMax() {
        when(tx.codeDelegations()).thenReturn(List.of(del));
        when(del.getChainId()).thenReturn(CHAIN_ID);
        when(del.nonce()).thenReturn(Account.MAX_NONCE);
        when(world.updater()).thenReturn(proxyWorldUpdater);

        final var p = new CodeDelegationProcessor(CHAIN_ID);
        final var result = p.process(world, tx.gasLimit(), tx.codeDelegations());

        assertNotNull(result);
        verify(proxyWorldUpdater).commit();
    }

    @Test
    void skipsWhenSAboveHalfOrder() {
        when(tx.codeDelegations()).thenReturn(List.of(del));
        when(del.getChainId()).thenReturn(CHAIN_ID);
        when(del.nonce()).thenReturn(0L);
        when(del.getS()).thenReturn(HALF_ORDER.add(BigInteger.ONE));
        when(world.updater()).thenReturn(proxyWorldUpdater);

        final var p = new CodeDelegationProcessor(CHAIN_ID);
        final var result = p.process(world, tx.gasLimit(), tx.codeDelegations());

        assertNotNull(result);
        verify(proxyWorldUpdater).commit();
    }

    @Test
    void skipsWhenRAboveHalfOrder() {
        when(tx.codeDelegations()).thenReturn(List.of(del));
        when(del.getChainId()).thenReturn(CHAIN_ID);
        when(del.nonce()).thenReturn(0L);
        when(del.getS()).thenReturn(HALF_ORDER.subtract(BigInteger.ONE));
        when(world.updater()).thenReturn(proxyWorldUpdater);

        final var p = new CodeDelegationProcessor(CHAIN_ID);
        final var result = p.process(world, tx.gasLimit(), tx.codeDelegations());

        assertNotNull(result);
        verify(proxyWorldUpdater).commit();
    }

    @Test
    void skipsWhenYParityTooBig() {
        when(tx.codeDelegations()).thenReturn(List.of(del));
        when(del.getChainId()).thenReturn(CHAIN_ID);
        when(del.nonce()).thenReturn(0L);
        when(del.getS()).thenReturn(HALF_ORDER.subtract(BigInteger.ONE));
        when(del.getYParity()).thenReturn(2 ^ 8);
        when(world.updater()).thenReturn(proxyWorldUpdater);

        final var p = new CodeDelegationProcessor(CHAIN_ID);
        final var result = p.process(world, tx.gasLimit(), tx.codeDelegations());

        assertNotNull(result);
        verify(proxyWorldUpdater).commit();
    }

    @Test
    void skipsWhenAuthoritySignatureIsEmpty() {
        when(tx.codeDelegations()).thenReturn(List.of(del));
        when(del.getChainId()).thenReturn(CHAIN_ID);
        when(del.nonce()).thenReturn(0L);
        when(del.getS()).thenReturn(HALF_ORDER.subtract(BigInteger.ONE));
        when(del.getYParity()).thenReturn(2 ^ 8 - 1);
        when(world.updater()).thenReturn(proxyWorldUpdater);

        try (MockedStatic<EthTxSigs> mocked = mockStatic(EthTxSigs.class)) {
            mocked.when(() -> EthTxSigs.extractAuthoritySignature(del)).thenReturn(Optional.empty());

            final var p = new CodeDelegationProcessor(CHAIN_ID);
            final var result = p.process(world, tx.gasLimit(), tx.codeDelegations());

            assertNotNull(result);
            verify(proxyWorldUpdater).commit();
        }
    }

    @Test
    void throwsWhenAuthoritySignatureNull() {
        when(tx.codeDelegations()).thenReturn(List.of(del));
        when(del.getChainId()).thenReturn(CHAIN_ID);
        when(del.nonce()).thenReturn(0L);
        when(del.getS()).thenReturn(HALF_ORDER);

        final var p = new CodeDelegationProcessor(CHAIN_ID);

        try (MockedStatic<EthTxSigs> mocked = mockStatic(EthTxSigs.class)) {
            mocked.when(() -> EthTxSigs.extractAuthoritySignature(del)).thenReturn(null);

            assertThrowsExactly(
                    NullPointerException.class, () -> p.process(world, tx.gasLimit(), tx.codeDelegations()));

            verify(world, never()).getAccount(any(Address.class));
            verify(world, never()).createAccount(any(Address.class));
        }
    }

    @Test
    void createsNewAccountAndDelegatesWhenMissingAndNonceZero() {
        final var acct = mock(MutableAccount.class);

        final var authAddr = Address.fromHexString("0x00000000000000000000000000000000000000AA");
        final var contractAddr = Address.fromHexString("0x00000000000000000000000000000000000000BB");
        final var expectedCode = Bytes.concatenate(CODE_DELEGATION_PREFIX, authAddr);

        when(tx.codeDelegations()).thenReturn(List.of(del));
        when(tx.gasLimit()).thenReturn(100000L);
        when(del.getChainId()).thenReturn(CHAIN_ID);
        when(del.nonce()).thenReturn(0L);
        when(del.getS()).thenReturn(BigInteger.ONE);
        when(del.getYParity()).thenReturn(1);
        when(del.address()).thenReturn(contractAddr.toArray());
        when(world.updater()).thenReturn(proxyWorldUpdater);

        when(proxyWorldUpdater.getAccount(authAddr)).thenReturn(null, acct);
        when(proxyWorldUpdater.lazyCreationCostInGas(authAddr)).thenReturn(25000L);
        when(proxyWorldUpdater.createAccountWithKeyAndCodeDelegation(authAddr, new byte[32], contractAddr))
                .thenReturn(true);

        try (MockedStatic<EthTxSigs> mocked = mockStatic(EthTxSigs.class)) {
            final var sig = mockAuthorityWithAddress(authAddr);
            mocked.when(() -> EthTxSigs.extractAuthoritySignature(del)).thenReturn(sig);

            final var p = new CodeDelegationProcessor(CHAIN_ID);
            final var result = p.process(world, tx.gasLimit(), tx.codeDelegations());

            assertNotNull(result);
            verify(proxyWorldUpdater, times(2)).getAccount(authAddr);
            verify(proxyWorldUpdater).createAccountWithKeyAndCodeDelegation(authAddr, new byte[32], contractAddr);
            verify(acct).incrementNonce();
            verify(proxyWorldUpdater).commit();
        }
    }

    @Test
    void createsProducesRecordWhenInsufficientGas() {
        final var acct = mock(MutableAccount.class);

        final var authAddr = Address.fromHexString("0x00000000000000000000000000000000000000AA");
        final var contractAddr = Address.fromHexString("0x00000000000000000000000000000000000000BB");
        final var expectedCode = Bytes.concatenate(CODE_DELEGATION_PREFIX, authAddr);

        when(tx.codeDelegations()).thenReturn(List.of(del));
        when(tx.gasLimit()).thenReturn(20000L);
        when(del.getChainId()).thenReturn(CHAIN_ID);
        when(del.nonce()).thenReturn(0L);
        when(del.getS()).thenReturn(BigInteger.ONE);
        when(del.getYParity()).thenReturn(1);
        when(del.address()).thenReturn(contractAddr.toArray());
        when(world.updater()).thenReturn(proxyWorldUpdater);

        when(proxyWorldUpdater.getAccount(authAddr)).thenReturn(null, acct);
        when(proxyWorldUpdater.lazyCreationCostInGas(authAddr)).thenReturn(25000L);
        when(proxyWorldUpdater.createNewChildRecordBuilder(CryptoCreateStreamBuilder.class, CRYPTO_CREATE))
                .thenReturn(cryptoCreateStreamBuilder);

        try (MockedStatic<EthTxSigs> mocked = mockStatic(EthTxSigs.class)) {
            final var sig = mockAuthorityWithAddress(authAddr);
            mocked.when(() -> EthTxSigs.extractAuthoritySignature(del)).thenReturn(sig);

            final var p = new CodeDelegationProcessor(CHAIN_ID);
            final var result = p.process(world, tx.gasLimit(), tx.codeDelegations());

            assertNotNull(result);
            verify(proxyWorldUpdater).getAccount(authAddr);
            verify(proxyWorldUpdater).createNewChildRecordBuilder(CryptoCreateStreamBuilder.class, CRYPTO_CREATE);
            verify(proxyWorldUpdater).commit();
        }
    }

    @Test
    void skipsCreatingWhenMissingButNonceNonZero() {
        final var authAddr = Address.fromHexString("0x00000000000000000000000000000000000000AB");
        final var contractAddr = Address.fromHexString("0x00000000000000000000000000000000000000BB");

        when(tx.codeDelegations()).thenReturn(List.of(del));
        when(del.getChainId()).thenReturn(CHAIN_ID);
        when(del.nonce()).thenReturn(7L);
        when(del.getS()).thenReturn(BigInteger.ONE);
        when(del.getYParity()).thenReturn(1);
        when(del.address()).thenReturn(contractAddr.toArray());
        when(world.updater()).thenReturn(proxyWorldUpdater);

        when(proxyWorldUpdater.getAccount(authAddr)).thenReturn(null);

        try (MockedStatic<EthTxSigs> mocked = mockStatic(EthTxSigs.class)) {
            final var sig = mockAuthorityWithAddress(authAddr);
            mocked.when(() -> EthTxSigs.extractAuthoritySignature(del)).thenReturn(sig);

            final var p = new CodeDelegationProcessor(CHAIN_ID);
            final var result = p.process(world, tx.gasLimit(), tx.codeDelegations());

            assertNotNull(result);
            verify(proxyWorldUpdater).getAccount(authAddr);
            verify(proxyWorldUpdater, never()).createAccount(any(Address.class));
        }
    }

    @Test
    void updatesExistingAccountWithEmptyCodeAndMatchingNonce() {
        final var acct = mock(HederaEvmAccount.class);

        final var authAddr = Address.fromHexString("0x00000000000000000000000000000000000000AC");
        final var contractAddr = Address.fromHexString("0x00000000000000000000000000000000000000BB");
        final var expectedCode = Bytes.concatenate(CODE_DELEGATION_PREFIX, authAddr);

        when(tx.codeDelegations()).thenReturn(List.of(del));
        when(del.getChainId()).thenReturn(CHAIN_ID);
        when(del.nonce()).thenReturn(5L);
        when(del.getS()).thenReturn(BigInteger.ONE);
        when(del.getYParity()).thenReturn(1);
        when(del.address()).thenReturn(contractAddr.toArray());
        when(world.updater()).thenReturn(proxyWorldUpdater);

        when(proxyWorldUpdater.getAccount(authAddr)).thenReturn(acct);
        when(acct.getCode()).thenReturn(Bytes.EMPTY);
        when(acct.getNonce()).thenReturn(5L);
        when(acct.isRegularAccount()).thenReturn(true);
        when(proxyWorldUpdater.setAccountCodeDelegation(any(), any())).thenReturn(true);

        try (MockedStatic<EthTxSigs> mocked = mockStatic(EthTxSigs.class)) {
            final var sig = mockAuthorityWithAddress(authAddr);
            mocked.when(() -> EthTxSigs.extractAuthoritySignature(del)).thenReturn(sig);

            final var p = new CodeDelegationProcessor(CHAIN_ID);
            final var result = p.process(world, tx.gasLimit(), tx.codeDelegations());

            assertNotNull(result);
            verify(proxyWorldUpdater).getAccount(authAddr);
            verify(proxyWorldUpdater, never()).createAccount(any(Address.class));
            verify(acct).incrementNonce();
        }
    }

    @Test
    void blocksWhenExistingAccountHasNonDelegatedNonEmptyCode() {
        final var acct = mock(MutableAccount.class);

        final var authAddr = Address.fromHexString("0x00000000000000000000000000000000000000AD");
        final var contractAddr = Address.fromHexString("0x00000000000000000000000000000000000000BB");

        when(tx.codeDelegations()).thenReturn(List.of(del));
        when(del.getChainId()).thenReturn(CHAIN_ID);
        when(del.nonce()).thenReturn(1L);
        when(del.getS()).thenReturn(BigInteger.ONE);
        when(del.getYParity()).thenReturn(1);
        when(del.address()).thenReturn(contractAddr.toArray());
        when(world.updater()).thenReturn(proxyWorldUpdater);

        when(proxyWorldUpdater.getAccount(authAddr)).thenReturn(acct);
        when(acct.getCode()).thenReturn(Bytes.fromHexString("0xabcdef"));

        try (MockedStatic<EthTxSigs> mocked = mockStatic(EthTxSigs.class)) {
            final var sig = mockAuthorityWithAddress(authAddr);
            mocked.when(() -> EthTxSigs.extractAuthoritySignature(del)).thenReturn(sig);

            final var p = new CodeDelegationProcessor(CHAIN_ID);
            final var result = p.process(world, tx.gasLimit(), tx.codeDelegations());

            assertNotNull(result);
            verify(proxyWorldUpdater).getAccount(authAddr);
            verify(acct, never()).setCode(any(Bytes.class));
            verify(acct, never()).incrementNonce();
        }
    }

    @Test
    void skipsWhenExistingAccountNonceMismatch() {
        final var acct = mock(MutableAccount.class);

        final var authAddr = Address.fromHexString("0x00000000000000000000000000000000000000AE");
        final var contractAddr = Address.fromHexString("0x00000000000000000000000000000000000000BB");

        when(tx.codeDelegations()).thenReturn(List.of(del));
        when(del.getChainId()).thenReturn(CHAIN_ID);
        when(del.nonce()).thenReturn(9L);
        when(del.getS()).thenReturn(BigInteger.ONE);
        when(del.getYParity()).thenReturn(1);
        when(del.address()).thenReturn(contractAddr.toArray());

        when(world.updater()).thenReturn(proxyWorldUpdater);
        when(proxyWorldUpdater.getAccount(authAddr)).thenReturn(acct);
        when(acct.getCode()).thenReturn(Bytes.EMPTY);
        when(acct.getNonce()).thenReturn(8L);

        try (MockedStatic<EthTxSigs> mocked = mockStatic(EthTxSigs.class)) {
            final var sig = mockAuthorityWithAddress(authAddr);
            mocked.when(() -> EthTxSigs.extractAuthoritySignature(del)).thenReturn(sig);

            final var p = new CodeDelegationProcessor(CHAIN_ID);
            final var result = p.process(world, tx.gasLimit(), tx.codeDelegations());

            assertNotNull(result);
            verify(proxyWorldUpdater).getAccount(authAddr);
            verify(acct, never()).setCode(any(Bytes.class));
            verify(acct, never()).incrementNonce();
        }
    }

    @Test
    void zeroAddressClearsCode() {
        final var del = mock(CodeDelegation.class);
        final var acct = mock(HederaEvmAccount.class);

        final var contractAddr = Address.fromHexString("0x00000000000000000000000000000000000000BB");
        final var zeroAddr = Address.ZERO;

        when(tx.codeDelegations()).thenReturn(List.of(del));
        when(del.getChainId()).thenReturn(CHAIN_ID);
        when(del.nonce()).thenReturn(0L);
        when(del.getS()).thenReturn(BigInteger.ONE);
        when(del.getYParity()).thenReturn(1);
        when(del.address()).thenReturn(contractAddr.toArray());
        when(world.updater()).thenReturn(proxyWorldUpdater);

        when(proxyWorldUpdater.getAccount(zeroAddr)).thenReturn(acct);
        when(acct.getCode()).thenReturn(Bytes.EMPTY);
        when(acct.getNonce()).thenReturn(0L);
        when(acct.isRegularAccount()).thenReturn(true);
        when(proxyWorldUpdater.setAccountCodeDelegation(any(), any())).thenReturn(true);

        try (MockedStatic<EthTxSigs> mocked = mockStatic(EthTxSigs.class)) {
            final var sig = mockAuthorityWithAddress(zeroAddr);
            mocked.when(() -> EthTxSigs.extractAuthoritySignature(del)).thenReturn(sig);

            final var p = new CodeDelegationProcessor(CHAIN_ID);
            final var result = p.process(world, tx.gasLimit(), tx.codeDelegations());

            assertNotNull(result);
            verify(proxyWorldUpdater).getAccount(zeroAddr);
            verify(acct).incrementNonce();
        }
    }

    @Test
    void getRandSReturnBigInteger() {
        final byte[] sampleChainId = new byte[] {0x01};
        final byte[] sampleAddress = Bytes.fromHexString("0x1122").toArray();
        final long sampleNonce = 42L;
        final int sampleY = 1;
        final byte[] sampleR = new byte[] {0x03, 0x04};
        final byte[] sampleS = new byte[] {0x05, 0x06};

        var cd = new CodeDelegation(sampleChainId, sampleAddress, sampleNonce, sampleY, sampleR, sampleS);
        assertEquals(new BigInteger(1, sampleR), cd.getR());
        assertEquals(new BigInteger(1, sampleS), cd.getS());
    }

    @Test
    void getYParityReturnsValue() {
        final byte[] sampleChainId = new byte[] {0x01};
        final byte[] sampleAddress = Bytes.fromHexString("0x1122").toArray();
        final long sampleNonce = 42L;
        final byte[] sampleR = new byte[] {0x03, 0x04};
        final byte[] sampleS = new byte[] {0x05, 0x06};
        var cd = new CodeDelegation(sampleChainId, sampleAddress, sampleNonce, 7, sampleR, sampleS);
        assertEquals(7, cd.getYParity());
    }
}

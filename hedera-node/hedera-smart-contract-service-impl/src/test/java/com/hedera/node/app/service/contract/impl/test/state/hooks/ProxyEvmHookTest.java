// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.state.hooks;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_HOOKS_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CODE_FACTORY;
import static com.hedera.node.app.service.token.HookDispatchUtils.HTS_HOOKS_CONTRACT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.node.app.service.contract.impl.state.EvmFrameState;
import com.hedera.node.app.service.contract.impl.state.hooks.ProxyEvmHook;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.Code;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProxyEvmHookTest {

    @Mock
    private EvmFrameState state;

    @Test
    void coversAllPathsWithAccountOwnedHook() {
        final var ownerAccountId = AccountID.newBuilder().accountNum(1234L).build();
        final var entityId = HookEntityId.newBuilder().accountId(ownerAccountId).build();
        final var hookId = HookId.newBuilder().entityId(entityId).build();

        final var hookContractId = ContractID.newBuilder().contractNum(777L).build();
        final var hookState = EvmHookState.newBuilder()
                .hookId(hookId)
                .hookContractId(hookContractId)
                .build();
        final Bytes hookContractCode = Bytes.fromHexString("0x6001600055");
        final Code expectedCode = CODE_FACTORY.createCode(hookContractCode, false);
        final Hash expectedHash = expectedCode.getCodeHash();

        given(state.getCode(hookContractId)).willReturn(hookContractCode);
        given(state.getCodeHash(hookContractId, CODE_FACTORY)).willReturn(expectedHash);

        // Storage expectations
        final var key = UInt256.valueOf(42);
        final var expectedStorageValue = UInt256.valueOf(4242);
        given(state.getStorageValue(HTS_HOOKS_CONTRACT_ID, key)).willReturn(expectedStorageValue);

        final var subject = new ProxyEvmHook(state, hookState, CODE_FACTORY);

        final var code = subject.getEvmCode(Bytes.wrap(new byte[] {1, 2, 3, 4}), CODE_FACTORY);
        assertEquals(expectedCode, code);
        assertEquals(hookContractCode, subject.getCode());
        assertEquals(expectedHash, subject.getCodeHash());
        assertEquals(HTS_HOOKS_CONTRACT_ADDRESS, subject.getAddress());
        assertEquals(HTS_HOOKS_CONTRACT_ID, subject.hederaContractId());
        assertEquals(expectedStorageValue, subject.getStorageValue(key));

        verify(state, times(2)).getCode(hookContractId);
        verify(state).getCodeHash(hookContractId, CODE_FACTORY);
        verify(state).getStorageValue(HTS_HOOKS_CONTRACT_ID, key);
    }

    @Test
    void coversContractOwnedHookBranchToo() {
        // --- Arrange: a hook whose owner is a CONTRACT (exercises asAccountId(..) branch) ---
        final var ownerContractId = ContractID.newBuilder().contractNum(555L).build();
        final var entityId =
                HookEntityId.newBuilder().contractId(ownerContractId).build();
        final var hookId = HookId.newBuilder().entityId(entityId).build();

        final var hookContractId = ContractID.newBuilder().contractNum(888L).build();

        final var hookState = EvmHookState.newBuilder()
                .hookId(hookId)
                .hookContractId(hookContractId)
                .build();

        final Bytes hookContractCode = Bytes.fromHexString("0x60FF");
        final Code expectedCode = CODE_FACTORY.createCode(hookContractCode, false);
        given(state.getCode(hookContractId)).willReturn(hookContractCode);
        given(state.getCodeHash(hookContractId, CODE_FACTORY)).willReturn(expectedCode.getCodeHash());

        final var subject = new ProxyEvmHook(state, hookState, CODE_FACTORY);

        assertEquals(expectedCode, subject.getEvmCode(Bytes.EMPTY, CODE_FACTORY));
        assertEquals(expectedCode.getCodeHash(), subject.getCodeHash());
    }

    @Test
    void constructorRejectsNullHookState() {
        assertThrows(NullPointerException.class, () -> new ProxyEvmHook(state, null, CODE_FACTORY));
    }
}

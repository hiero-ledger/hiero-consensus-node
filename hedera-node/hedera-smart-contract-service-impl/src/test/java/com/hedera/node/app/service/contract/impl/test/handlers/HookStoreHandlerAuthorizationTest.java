// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static com.hedera.hapi.util.HapiUtils.EMPTY_KEY_LIST;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.AN_ED25519_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.hooks.HookStoreTransactionBody;
import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.hapi.node.state.hooks.HookType;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.ReadableEvmHookStore;
import com.hedera.node.app.service.contract.impl.handlers.HookStoreHandler;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HookStoreHandlerAuthorizationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void hollowOwnerStillRequiresFinalizationWithOrWithoutAdminKey(final boolean hasAdminKey) throws PreCheckException {
        final var payerId = AccountID.newBuilder().accountNum(1001).build();
        final var ownerId = AccountID.newBuilder().accountNum(1002).build();
        final var payer =
                Account.newBuilder().accountId(payerId).key(AN_ED25519_KEY).build();
        final var owner = Account.newBuilder()
                .accountId(ownerId)
                .key(EMPTY_KEY_LIST)
                .alias(Bytes.wrap(new byte[20]))
                .build();
        final var hookId = HookId.newBuilder()
                .entityId(HookEntityId.newBuilder().accountId(ownerId))
                .hookId(1)
                .build();
        final var hook = EvmHookState.newBuilder().hookId(hookId).type(HookType.EVM_HOOK);
        if (hasAdminKey) {
            hook.adminKey(AN_ED25519_KEY);
        }
        final var accounts = mock(ReadableAccountStore.class);
        given(accounts.getAccountById(payerId)).willReturn(payer);
        given(accounts.getAccountById(ownerId)).willReturn(owner);
        final var hooks = mock(ReadableEvmHookStore.class);
        given(hooks.getEvmHook(hookId)).willReturn(hook.build());
        final var txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(payerId))
                .hookStore(HookStoreTransactionBody.newBuilder().hookId(hookId))
                .build();
        final var context = new FakePreHandleContext(accounts, txn);
        context.registerStore(ReadableEvmHookStore.class, hooks);

        new HookStoreHandler().preHandle(context);

        assertThat(context.requiredHollowAccounts()).containsExactly(owner);
        assertThat(context.requiredKeyRequirements()).isEmpty();
    }
}

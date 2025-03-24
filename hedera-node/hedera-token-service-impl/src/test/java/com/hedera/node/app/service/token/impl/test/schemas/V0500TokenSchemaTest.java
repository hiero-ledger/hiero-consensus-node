// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.schemas;

import static com.hedera.hapi.util.HapiUtils.CONTRACT_ID_COMPARATOR;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.token.impl.schemas.V0500TokenSchema;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.EntityIdFactory;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("V050 first storage keys repair")
@ExtendWith(MockitoExtension.class)
class V0500TokenSchemaTest {
    private static final int N = 3;
    private static final String SHARED_VALUES_KEY = "V0500_FIRST_STORAGE_KEYS";
    private static final SortedMap<ContractID, Bytes> FIRST_KEYS = new TreeMap<>(CONTRACT_ID_COMPARATOR) {
        {
            for (long i = 1; i <= N; i++) {
                put(contractIdWith(i), Bytes.wrap(copyToLeftPaddedByteArray(i, new byte[32])));
            }
        }
    };
    private final Map<String, Object> sharedValues = new HashMap<>();
    private final Map<AccountID, Account> accounts = new HashMap<>();
    private final MapWritableKVState<AccountID, Account> writableAccounts =
            new MapWritableKVState<>(ACCOUNTS_KEY, accounts);
    private final MapWritableStates writableStates =
            MapWritableStates.builder().state(writableAccounts).build();

    @Mock
    private MigrationContext ctx;

    @Mock
    private EntityIdFactory entityIdFactory;

    private V0500TokenSchema subject;

    @BeforeEach
    void setUp() {
        subject = new V0500TokenSchema(entityIdFactory);
    }

    @Test
    @DisplayName("skips migration without shared values")
    void throwsWithoutSharedValues() {
        given(ctx.sharedValues()).willReturn(sharedValues);
        assertDoesNotThrow(() -> subject.migrate(ctx));
    }

    @Test
    @DisplayName("works around missing account")
    void worksAroundMissing() {
        givenValidCtx();
        given(entityIdFactory.newAccountId(anyLong())).willReturn(AccountID.DEFAULT);
        assertDoesNotThrow(() -> subject.migrate(ctx));
    }

    @Test
    @DisplayName("fixes first storage keys")
    void fixesFirstKeys() {
        givenValidCtx();
        given(entityIdFactory.newAccountId(1))
                .willReturn(AccountID.newBuilder().accountNum(1L).build());
        given(entityIdFactory.newAccountId(2))
                .willReturn(AccountID.newBuilder().accountNum(2L).build());
        given(entityIdFactory.newAccountId(3))
                .willReturn(AccountID.newBuilder().accountNum(3L).build());
        accounts.put(
                accountIdWith(1),
                Account.newBuilder()
                        .smartContract(true)
                        .firstContractStorageKey(Bytes.EMPTY)
                        .build());
        accounts.put(
                accountIdWith(2),
                Account.newBuilder()
                        .firstContractStorageKey(FIRST_KEYS.get(contractIdWith(2)))
                        .build());
        accounts.put(
                accountIdWith(3),
                Account.newBuilder()
                        .firstContractStorageKey(FIRST_KEYS.get(contractIdWith(4)))
                        .build());

        subject.migrate(ctx);
        writableStates.commit();

        FIRST_KEYS.forEach((contractId, firstKey) -> assertThat(accounts)
                .hasEntrySatisfying(
                        accountIdWith(contractId.contractNumOrThrow()),
                        account -> assertThat(account.firstContractStorageKey()).isEqualTo(firstKey)));
    }

    private void givenValidCtx() {
        given(ctx.newStates()).willReturn(writableStates);
        given(ctx.sharedValues()).willReturn(sharedValues);
        sharedValues.put(SHARED_VALUES_KEY, FIRST_KEYS);
    }

    private static ContractID contractIdWith(final long num) {
        return ContractID.newBuilder().contractNum(num).build();
    }

    private static AccountID accountIdWith(final long num) {
        return AccountID.newBuilder().accountNum(num).build();
    }

    private static byte[] copyToLeftPaddedByteArray(long value, final byte[] dest) {
        for (int i = 7, j = dest.length - 1; i >= 0; i--, j--) {
            dest[j] = (byte) (value & 0xffL);
            value >>= 8;
        }
        return dest;
    }
}

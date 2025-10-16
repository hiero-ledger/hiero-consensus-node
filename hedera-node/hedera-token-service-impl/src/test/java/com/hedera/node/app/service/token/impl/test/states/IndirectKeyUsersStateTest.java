// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.states;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.IndirectKeyUsersKey;
import com.hedera.hapi.node.state.token.IndirectKeyUsersValue;
import com.hedera.node.app.service.token.impl.schemas.V0620TokenSchema;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IndirectKeyUsersStateTest {

    private AccountID acct(long num) {
        return AccountID.newBuilder().accountNum(num).build();
    }

    private IndirectKeyUsersKey key(AccountID keyAccount, AccountID user) {
        return IndirectKeyUsersKey.newBuilder()
                .keyAccountId(keyAccount)
                .indirectUserId(user)
                .build();
    }

    private IndirectKeyUsersValue val(AccountID prev, AccountID next) {
        return IndirectKeyUsersValue.newBuilder()
                .prevUserId(prev)
                .nextUserId(next)
                .build();
    }

    @Test
    @DisplayName("insert and remove update prev/next pointers as expected")
    void insertRemovePointers() {
        // Build an in-memory writable state for INDIRECT_KEY_USERS
        final var state = MapWritableKVState.<IndirectKeyUsersKey, IndirectKeyUsersValue>builder(
                        V0620TokenSchema.INDIRECT_KEY_USERS_STATE_ID, V0620TokenSchema.INDIRECT_KEY_USERS_STATE_LABEL)
                .build();

        final var A = acct(1001);
        final var U1 = acct(2001);
        final var U2 = acct(2002);
        final var U3 = acct(2003);

        // Insert U1 as the first/only element: prev = DEFAULT, next = DEFAULT
        state.put(key(A, U1), val(AccountID.DEFAULT, AccountID.DEFAULT));
        assertThat(state.get(key(A, U1))).isEqualTo(val(AccountID.DEFAULT, AccountID.DEFAULT));

        // Insert U2 at tail: update U1.next -> U2, set U2.prev -> U1
        state.put(key(A, U1), val(AccountID.DEFAULT, U2));
        state.put(key(A, U2), val(U1, AccountID.DEFAULT));
        assertThat(state.get(key(A, U1)).nextUserId()).isEqualTo(U2);
        assertThat(state.get(key(A, U2)).prevUserId()).isEqualTo(U1);

        // Insert U3 at tail: update U2.next -> U3, set U3.prev -> U2
        state.put(key(A, U2), val(U1, U3));
        state.put(key(A, U3), val(U2, AccountID.DEFAULT));
        assertThat(state.get(key(A, U2)).nextUserId()).isEqualTo(U3);
        assertThat(state.get(key(A, U3)).prevUserId()).isEqualTo(U2);

        // Remove U2: update U1.next -> U3, update U3.prev -> U1, remove (A,U2)
        state.put(key(A, U1), val(AccountID.DEFAULT, U3));
        state.put(key(A, U3), val(U1, AccountID.DEFAULT));
        state.remove(key(A, U2));

        // Verify pointers around removed middle element
        final var v1 = state.get(key(A, U1));
        final var v3 = state.get(key(A, U3));
        assertThat(v1.prevUserId()).isEqualTo(AccountID.DEFAULT);
        assertThat(v1.nextUserId()).isEqualTo(U3);
        assertThat(v3.prevUserId()).isEqualTo(U1);
        assertThat(v3.nextUserId()).isEqualTo(AccountID.DEFAULT);
        assertThat(state.get(key(A, U2))).isNull();
    }
}


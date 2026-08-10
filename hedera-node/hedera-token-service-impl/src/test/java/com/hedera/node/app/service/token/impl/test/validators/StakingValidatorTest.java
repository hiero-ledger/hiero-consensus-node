// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_STAKING_ID;
import static com.hedera.node.app.service.token.api.AccountSummariesApi.SENTINEL_ACCOUNT_ID;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.validators.StakingValidator;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.workflows.HandleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StakingValidatorTest {
    private static final String STAKED_ACCOUNT_ID = "STAKED_ACCOUNT_ID";

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private NetworkInfo networkInfo;

    @Mock
    private Account account;

    private void validateUpdate(final AccountID stakedAccountId) {
        StakingValidator.validateStakedIdForUpdate(
                false, STAKED_ACCOUNT_ID, stakedAccountId, null, accountStore, networkInfo);
    }

    @Test
    void sentinelAccountIdSkipsTheExistenceCheck() {
        assertDoesNotThrow(() -> validateUpdate(SENTINEL_ACCOUNT_ID));
        // 0.0.0 means "reset staking", so there is no account to resolve
        verifyNoInteractions(accountStore);
    }

    @Test
    void numZeroInAnotherShardOrRealmIsNotTheSentinel() {
        // Only 0.0.0 is the sentinel. A num-only sentinel test would let these skip validation
        // and then be persisted verbatim as the staking target by the calling handlers.
        for (final var rogue : new AccountID[] {
            AccountID.newBuilder().shardNum(9).realmNum(9).accountNum(0).build(),
            AccountID.newBuilder().shardNum(1).realmNum(0).accountNum(0).build(),
            AccountID.newBuilder().shardNum(0).realmNum(2).accountNum(0).build()
        }) {
            given(accountStore.getAccountById(rogue)).willReturn(null);

            final var failure = assertThrows(HandleException.class, () -> validateUpdate(rogue));
            assertEquals(INVALID_STAKING_ID, failure.getStatus(), "expected rejection for " + rogue);
        }
    }

    @Test
    void existingStakedAccountIsAccepted() {
        final var stakedAccountId = AccountID.newBuilder().accountNum(3).build();
        given(accountStore.getAccountById(stakedAccountId)).willReturn(account);

        assertDoesNotThrow(() -> validateUpdate(stakedAccountId));
    }

    @Test
    void missingStakedAccountIsRejected() {
        final var stakedAccountId = AccountID.newBuilder().accountNum(3).build();
        given(accountStore.getAccountById(stakedAccountId)).willReturn(null);

        final var failure = assertThrows(HandleException.class, () -> validateUpdate(stakedAccountId));
        assertEquals(INVALID_STAKING_ID, failure.getStatus());
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.accounts;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.otter.fixtures.app.OtterService;
import org.hiero.otter.fixtures.app.model.Account;
import org.hiero.otter.fixtures.app.model.AccountId;
import org.hiero.otter.fixtures.app.model.EntityIdGenerator;
import org.hiero.otter.fixtures.app.state.OtterServiceStateSpecification;
import org.hiero.otter.fixtures.app.state.OtterStateId;
import org.hiero.otter.fixtures.network.transactions.CreateAccountTransaction;
import org.hiero.otter.fixtures.network.transactions.DeleteAccountTransaction;
import org.hiero.otter.fixtures.network.transactions.OtterTransaction;

/**
 * A simple service to manage accounts. The service has nothing to do with real
 * account processing, accounts are only used to make sure network state contains
 * lots of elements. Some issues are reproducible only when the state is large,
 * not a few singletons or KVs. This service is a way to create such large states.
 */
public class AccountsService implements OtterService {

    private static final Logger log = LogManager.getLogger();

    private static final String NAME = "AccountsService";

    private static final AccountsStateSpecification STATE_SPECIFICATION = new AccountsStateSpecification();

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull String name() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull OtterServiceStateSpecification stateSpecification() {
        return STATE_SPECIFICATION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handleTransaction(
            @NonNull final WritableStates writableStates,
            @NonNull final ConsensusEvent event,
            @NonNull final OtterTransaction transaction,
            @NonNull final Instant transactionTimestamp,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {
        switch (transaction.getDataCase()) {
            case CREATEACCOUNTTRANSACTION ->
                handleCreateAccount(writableStates, transaction.getCreateAccountTransaction());
            case DELETEACCOUNTTRANSACTION ->
                handleDeleteAccount(writableStates, transaction.getDeleteAccountTransaction());
        }
    }

    private void handleCreateAccount(
            @NonNull final WritableStates writableStates,
            @NonNull final CreateAccountTransaction createAccountTransaction) {
        final WritableSingletonState<EntityIdGenerator> entityIdState =
                writableStates.getSingleton(OtterStateId.ENTITYID_GENERATOR_STATE_ID.id());
        final EntityIdGenerator gen = entityIdState.get();
        assert gen != null;

        final long accountId = gen.nextId();
        entityIdState.put(gen.copyBuilder().nextId(accountId + 1).build());

        final WritableKVState<AccountId, Account> accountsState =
                writableStates.get(OtterStateId.ACCOUNTS_STATE_ID.id());
        final AccountId id = new AccountId(accountId);
        final String accountName = createAccountTransaction.getName();
        final Account account = new Account(id, accountName);
        accountsState.put(id, account);

        log.info(DEMO_INFO.getMarker(), "Account created: id={} name={}", id.id(), accountName);
    }

    private void handleDeleteAccount(
            @NonNull final WritableStates writableStates,
            @NonNull final DeleteAccountTransaction deleteAccountTransaction) {
        final WritableKVState<AccountId, Account> accountsState =
                writableStates.get(OtterStateId.ACCOUNTS_STATE_ID.id());
        final AccountId id = new AccountId(deleteAccountTransaction.getId());
        if (!accountsState.contains(id)) {
            log.info(DEMO_INFO.getMarker(), "Account not deleted, doesn't exist: id={}", id.id());
            return;
        }
        accountsState.remove(id);

        log.info(DEMO_INFO.getMarker(), "Account deleted: id={}", id.id());
    }
}

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
import org.hiero.consensus.model.event.Event;
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
import org.jetbrains.annotations.NotNull;

public class AccountsService implements OtterService {

    private static final Logger log = LogManager.getLogger();

    private static final String NAME = "AccountsService";

    private static final AccountsStateSpecification STATE_SPECIFICATION = new AccountsStateSpecification();

    @Override
    public @NotNull String name() {
        return NAME;
    }

    @Override
    public @NotNull OtterServiceStateSpecification stateSpecification() {
        return STATE_SPECIFICATION;
    }

    @Override
    public void handleTransaction(
            @NotNull final WritableStates writableStates,
            @NotNull final ConsensusEvent event,
            @NotNull final OtterTransaction transaction,
            @NotNull final Instant transactionTimestamp,
            @NotNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {
        switch (transaction.getDataCase()) {
            case CREATEACCOUNTTRANSACTION ->
                handleCreateAccount(writableStates, transaction.getCreateAccountTransaction());
            case DELETEACCOUNTTRANSACTION ->
                handleDeleteAccount(writableStates, transaction.getDeleteAccountTransaction());
        }
    }

    @Override
    public void preHandleTransaction(
            @NotNull final Event event,
            @NotNull final OtterTransaction transaction,
            @NotNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {}

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

//        log.info(DEMO_INFO.getMarker(), "Account created: id={} name={}", id.id(), accountName);
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

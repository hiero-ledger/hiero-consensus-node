// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.crypto;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.model.transaction.Transaction;

/**
 * This class handles the lifecycle events for the {@link CryptocurrencyDemoState}.
 */
public class CryptocurrencyDemoConsensusStateEventHandler
        implements ConsensusStateEventHandler<CryptocurrencyDemoState> {

    private static final Logger logger = LogManager.getLogger(CryptocurrencyDemoConsensusStateEventHandler.class);

    @Override
    public void onStateInitialized(
            @NonNull final CryptocurrencyDemoState state,
            @NonNull final Platform platform,
            @NonNull final InitTrigger trigger,
            @Nullable final SemanticVersion previousVersion) {
        if (trigger == InitTrigger.GENESIS) {
            state.genesisInit(platform);
        }
    }

    @Override
    public void onHandleConsensusRound(
            @NonNull final Round round,
            @NonNull final CryptocurrencyDemoState state,
            @NonNull
                    final Consumer<ScopedSystemTransaction<StateSignatureTransaction>>
                            stateSignatureTransactionCallback) {
        state.throwIfImmutable();
        round.forEachEventTransaction((event, transaction) -> {
            if (areTransactionBytesSystemOnes(transaction)) {
                consumeSystemTransaction(transaction, event, stateSignatureTransactionCallback);
            }

            handleTransaction(event.getCreatorId(), transaction, state);
        });
    }

    /**
     * The matching algorithm for any given stock is as follows. The first bid or ask for a stock is
     * remembered. Then, if there is a higher bid or lower ask, it is remembered, replacing the earlier one.
     * Eventually, there will be a bid that is equal to or greater than the ask. At that point, they are
     * matched, and a trade occurs, selling one share at the average of the bid and ask. Then the stored bid
     * and ask are erased, and it goes back to waiting for a bid or ask to remember.
     * <p>
     * If a member tries to sell a stock for which they own no shares, or if they try to buy a stock at a
     * price higher than the amount of money they currently have, then their bid/ask for that stock will not
     * be stored.
     * <p>
     * A transaction is 1 or 3 bytes:
     *
     * <pre>
     * {SLOW} = run slowly
     * {FAST} = run quickly
     * {BID,s,p} = bid to buy 1 share of stock s at p cents (where 0 &lt;= p &lt;= 127)
     * {ASK,s,p} = ask to sell 1 share of stock s at p cents (where 1 &lt;= p &lt;= 127)
     * </pre>
     */
    private void handleTransaction(
            @NonNull final NodeId id,
            @NonNull final Transaction transaction,
            @NonNull final CryptocurrencyDemoState state) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(transaction, "transaction must not be null");

        final Bytes contents = transaction.getApplicationTransaction();
        if (contents.length() < 3) {
            return;
        }
        if (contents.getByte(0) == CryptocurrencyDemoState.TransType.slow.ordinal()
                || contents.getByte(0) == CryptocurrencyDemoState.TransType.fast.ordinal()) {
            return;
        }
        final int askBid = contents.getByte(0);
        final int tradeStock = contents.getByte(1);
        int tradePrice = contents.getByte(2);

        if (tradePrice < 1 || tradePrice > 127) {
            return; // all asks and bids must be in the range 1 to 127
        }

        state.handleTransaction(id, askBid, tradeStock, tradePrice);
    }

    @Override
    public void onPreHandle(
            @NonNull Event event,
            @NonNull CryptocurrencyDemoState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {

        event.forEachTransaction(t -> {
            if (areTransactionBytesSystemOnes(t)) {
                consumeSystemTransaction(t, event, stateSignatureTransactionCallback);
            }
        });
    }

    @Override
    public boolean onSealConsensusRound(@NonNull Round round, @NonNull CryptocurrencyDemoState state) {
        // no-op
        return true;
    }

    @Override
    public void onUpdateWeight(
            @NonNull CryptocurrencyDemoState state,
            @NonNull AddressBook configAddressBook,
            @NonNull PlatformContext context) {
        // no-op
    }

    @Override
    public void onNewRecoveredState(@NonNull CryptocurrencyDemoState recoveredState) {
        // no-op
    }

    private void consumeSystemTransaction(
            final Transaction transaction,
            final Event event,
            final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        try {
            final var stateSignatureTransaction =
                    StateSignatureTransaction.PROTOBUF.parse(transaction.getApplicationTransaction());
            stateSignatureTransactionCallback.accept(new ScopedSystemTransaction<>(
                    event.getCreatorId(), event.getBirthRound(), stateSignatureTransaction));
        } catch (final ParseException e) {
            logger.error("Failed to parse StateSignatureTransaction", e);
        }
    }

    /**
     * Checks if the transaction bytes are system ones. The test creates application transactions
     * with a byte[] with no more than 3 single bytes inside. System transactions will be bigger than that.
     *
     * @param transaction the consensus transaction to check
     * @return true if the transaction bytes are system ones, false otherwise
     */
    private boolean areTransactionBytesSystemOnes(final Transaction transaction) {
        return transaction.getApplicationTransaction().length() > 3;
    }
}

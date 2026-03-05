// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.priorityAddressOf;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.LogBuilder;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.token.ReadableAccountStore;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Helper for logging ERC transfer events for fungible and non-fungible transfers.
 */
public class TransferEventLoggingUtils {

    // Keccak-256 hash of the event signature "Transfer(address,address,uint256)".
    // This hash is used as the topic0 in Ethereum logs to identify Transfer events.
    private static final Bytes TRANSFER_EVENT =
            Bytes.fromHexString("ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef");

    private TransferEventLoggingUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Emit ERC events for all non-HRAB token transfers
     *
     * @param recordBuilder stream builder, the result of the dispatch
     * @param accountStore  the readable account store
     * @param frame         the message frame
     */
    public static void emitErcLogEventsFor(
            @NonNull final ContractCallStreamBuilder recordBuilder,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final MessageFrame frame) {
        if (recordBuilder.tokenTransferLists() != null) {
            for (TokenTransferList transfer : recordBuilder.tokenTransferLists()) {
                if (!transfer.transfers().isEmpty()) {
                    logSuccessfulFungibleTransfer(transfer.tokenOrThrow(), transfer.transfers(), accountStore, frame);
                }
                if (!transfer.nftTransfers().isEmpty()) {
                    for (final NftTransfer nftTransfer : transfer.nftTransfers()) {
                        logSuccessfulNftTransfer(transfer.tokenOrThrow(), nftTransfer, accountStore, frame);
                    }
                }
            }
        }
    }

    /**
     * Holder for AccountAmount values. Sorted as a Comparable implementation to try to produce fewer transfer events.
     */
    private static class AccountChange implements Comparable<AccountChange> {

        private static final Comparator<AccountChange> COMPARATOR = Comparator.<AccountChange>comparingLong(
                        e -> e.amount)
                .reversed() // amount DESC
                .thenComparing(e -> e.accountId.shardNum()) // shard ASC
                .thenComparing(e -> e.accountId.realmNum()) // realm ASC
                .thenComparing(e -> e.accountId.hasAccountNum() ? e.accountId.accountNum() : 0L); // accountNum ASC

        public final AccountID accountId;
        public long amount; // using not final var because it will be changed during the conversion algorithm

        public AccountChange(final AccountID accountId, long amount) {
            this.accountId = accountId;
            this.amount = amount;
        }

        @Override
        public int compareTo(@NonNull final TransferEventLoggingUtils.AccountChange other) {
            return COMPARATOR.compare(this, other);
        }
    }

    /**
     * Logs a successful ERC-20 transfer event based on the Hedera-style representation
     * of fungible balance adjustments.
     * <p>The implementation supports the following scenarios:</p>
     * <ul>
     * <li><b>1. Regular transfer:</b>
     * <ul>
     * <li>Account A: amount -10</li>
     * <li>Account B: amount +10</li>
     * </ul>
     * </li>
     * <li><b>2. Airdropped token with custom fees:</b>
     * <p>When "net of transfers = true" and fees are paid by the sender on behalf of the receiver:</p>
     * <p>Or when using 'transferTokens' with multiple receivers</p>
     * <ul>
     * <li>Account A (Sender): amount -11</li>
     * <li>Account B (Fee Collector): amount +1</li>
     * <li>Account C (Receiver): amount +10</li>
     * </ul>
     * </li>
     * <li><b>3. Multiple sender scenarios:</b>
     * <p>Situations where adjustments involve multiple debit accounts (reserved for future use cases):</p>
     * <ul>
     * <li>Account A: amount -1</li>
     * <li>Account B: amount -10</li>
     * <li>Account C: amount +11</li>
     * </ul>
     * </li>
     * </ul>
     *
     * @param tokenId      the token ID
     * @param adjusts      the Hedera-style representation of the fungible balance adjustments
     * @param accountStore the account store to get account addresses from
     * @param frame        the frame to log to
     */
    public static void logSuccessfulFungibleTransfer(
            @NonNull final TokenID tokenId,
            @NonNull final List<AccountAmount> adjusts,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final MessageFrame frame) {
        requireNonNull(tokenId);
        requireNonNull(frame);
        requireNonNull(adjusts);
        requireNonNull(accountStore);
        List<AccountChange> senders = new ArrayList<>();
        List<AccountChange> receivers = new ArrayList<>();

        // 1. Separate accounts by sign, negative are senders, positive are receivers of the transfer
        for (AccountAmount account : adjusts) {
            if (account.amount() < 0) {
                senders.add(new AccountChange(account.accountIDOrThrow(), Math.abs(account.amount())));
            } else if (account.amount() > 0) {
                receivers.add(new AccountChange(account.accountIDOrThrow(), account.amount()));
            }
        }
        // 2. Sort both senders and receivers to prevent "random ordering" of resulted ERC events.
        // For sort order see AccountChange.compareTo
        Collections.sort(senders);
        Collections.sort(receivers);

        // 3. Convert senders/receivers to transfer events
        int sIdx = 0;
        int rIdx = 0;
        while (sIdx < senders.size() && rIdx < receivers.size()) {
            AccountChange sender = senders.get(sIdx);
            AccountChange receiver = receivers.get(rIdx);
            long amount = Math.min(sender.amount, receiver.amount);
            // create transfer event
            frame.addLog(builderFor(tokenId, sender.accountId, receiver.accountId, accountStore)
                    .forDataItem(amount)
                    .build());
            // change the amounts according to transfer event amount
            sender.amount -= amount;
            receiver.amount -= amount;
            if (sender.amount == 0) sIdx++;
            if (receiver.amount == 0) rIdx++;
        }
    }

    /**
     * Logs a successful ERC-721 transfer event based on the Hedera-style representation of the NFT ownership change.
     *
     * @param tokenId      the token ID
     * @param nftTransfer  the Hedera-style representation of the NFT ownership change
     * @param accountStore the account store to get account addresses from
     * @param frame        the frame to log to
     */
    public static void logSuccessfulNftTransfer(
            @NonNull final TokenID tokenId,
            @NonNull final NftTransfer nftTransfer,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final MessageFrame frame) {
        requireNonNull(tokenId);
        requireNonNull(frame);
        requireNonNull(nftTransfer);
        requireNonNull(accountStore);
        frame.addLog(builderFor(
                        tokenId,
                        nftTransfer.senderAccountIDOrThrow(),
                        nftTransfer.receiverAccountIDOrThrow(),
                        accountStore)
                .forIndexedArgument(BigInteger.valueOf(nftTransfer.serialNumber()))
                .build());
    }

    private static LogBuilder builderFor(
            @NonNull final TokenID tokenId,
            @NonNull final AccountID senderId,
            @NonNull final AccountID receiverId,
            @NonNull final ReadableAccountStore accountStore) {
        final var tokenAddress = asLongZeroAddress(tokenId.tokenNum());
        final var senderAddress = priorityAddressOf(requireNonNull(accountStore.getAliasedAccountById(senderId)));
        final var receiverAddress = priorityAddressOf(requireNonNull(accountStore.getAliasedAccountById(receiverId)));
        return LogBuilder.logBuilder()
                .forLogger(tokenAddress)
                .forEventSignature(TRANSFER_EVENT)
                .forIndexedArgument(senderAddress)
                .forIndexedArgument(receiverAddress);
    }
}

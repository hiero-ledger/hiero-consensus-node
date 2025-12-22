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
                    for (int i = 0; i < transfer.transfers().size(); i += 2) {
                        logSuccessfulFungibleTransfer(
                                transfer.tokenOrThrow(),
                                transfer.transfers().get(i), // credit
                                transfer.transfers().get(i + 1), // debit
                                accountStore,
                                frame);
                    }
                }
                if (!transfer.nftTransfers().isEmpty()) {
                    for (NftTransfer nftTransfer : transfer.nftTransfers()) {
                        logSuccessfulNftTransfer(transfer.tokenOrThrow(), nftTransfer, accountStore, frame);
                    }
                }
            }
        }
    }

    //TODO Glib: support -1,-10,+11 transfers
    /**
     * Logs a successful ERC-20 transfer event based on the Hedera-style representation of the fungible
     * balance adjustments.
     *
     * <p><b>IMPORTANT:</b> The adjusts list must be length two.
     *
     * @param tokenId      the token ID
     * @param party1       the Hedera-style representation of the fungible balance adjustments, 1st party
     * @param party2       the Hedera-style representation of the fungible balance adjustments, 2nd party
     * @param accountStore the account store to get account addresses from
     * @param frame        the frame to log to
     */
    public static void logSuccessfulFungibleTransfer(
            @NonNull final TokenID tokenId,
            @NonNull final AccountAmount party1,
            @NonNull final AccountAmount party2,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final MessageFrame frame) {
        requireNonNull(tokenId);
        requireNonNull(frame);
        requireNonNull(party1);
        requireNonNull(party2);
        requireNonNull(accountStore);
        final AccountAmount debit;
        final AccountAmount credit;
        if (party1.amount() < 0) {
            debit = party1;
            credit = party2;
        } else {
            debit = party2;
            credit = party1;
        }
        frame.addLog(builderFor(tokenId, debit.accountIDOrThrow(), credit.accountIDOrThrow(), accountStore)
                .forDataItem(credit.amount())
                .build());
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

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersCall.transferGasRequirement;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.Erc721TransferFromTranslator.ERC_721_TRANSFER_FROM;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.TransferEventLoggingUtils.logSuccessfulNftTransfer;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements the ERC-721 {@code transferFrom()} call of the HTS contract.
 */
public class Erc721TransferFromCall extends AbstractCall {
    private final long serialNo;
    private final Address from;
    private final Address to;
    private final TokenID tokenId;
    private final VerificationStrategy verificationStrategy;
    private final AccountID senderId;
    private final AddressIdConverter addressIdConverter;
    private final SpecialRewardReceivers specialRewardReceivers;

    /**
     * @param serialNo the serial number of the ERC721 token
     * @param from the address of the account from which the token will be transferred
     * @param to the address of the account to which the token will be transferred
     * @param tokenId the token id of the token to be transferred
     * @param verificationStrategy the verification strategy used in this call
     * @param enhancement the enhancement used in this call
     * @param gasCalculator the gas calculator used in this call
     * @param senderId the sender id of the sending account
     * @param addressIdConverter the address ID converter for this call
     * @param specialRewardReceivers the special reward receiver
     */
    // too many parameters
    @SuppressWarnings("java:S107")
    public Erc721TransferFromCall(
            final long serialNo,
            @NonNull final Address from,
            @NonNull final Address to,
            @NonNull final TokenID tokenId,
            @NonNull final VerificationStrategy verificationStrategy,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final AccountID senderId,
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final SpecialRewardReceivers specialRewardReceivers) {
        super(gasCalculator, enhancement, false);
        this.from = requireNonNull(from);
        this.to = requireNonNull(to);
        this.tokenId = tokenId;
        this.verificationStrategy = requireNonNull(verificationStrategy);
        this.senderId = requireNonNull(senderId);
        this.addressIdConverter = requireNonNull(addressIdConverter);
        this.serialNo = serialNo;
        this.specialRewardReceivers = requireNonNull(specialRewardReceivers);
    }

    @NonNull
    @Override
    public PricedResult execute(final MessageFrame frame) {
        // https://eips.ethereum.org/EIPS/eip-721
        if (tokenId == null) {
            return reversionWith(INVALID_TOKEN_ID, gasCalculator.canonicalGasRequirement(DispatchType.TRANSFER_NFT));
        }
        final var syntheticTransfer = syntheticTransfer(senderId);
        final var gasRequirement = transferGasRequirement(syntheticTransfer, gasCalculator, enhancement, senderId);
        final var recordBuilder = systemContractOperations()
                .dispatch(syntheticTransfer, verificationStrategy, senderId, ContractCallStreamBuilder.class);
        final var status = recordBuilder.status();
        if (status != ResponseCodeEnum.SUCCESS) {
            return gasOnly(revertResult(recordBuilder, gasRequirement), status, false);
        } else {
            final var nftTransfer = syntheticTransfer
                    .cryptoTransferOrThrow()
                    .tokenTransfers()
                    .get(0)
                    .nftTransfers()
                    .get(0);
            logSuccessfulNftTransfer(tokenId, nftTransfer, readableAccountStore(), frame);
            return gasOnly(
                    successResult(
                            ERC_721_TRANSFER_FROM.getOutputs().encode(Tuple.EMPTY), gasRequirement, recordBuilder),
                    status,
                    false);
        }
    }

    private TransactionBody syntheticTransfer(@NonNull final AccountID spenderId) {
        // To get isApproval we need the actual owner, which can be different from 'from'
        final var ownerId = getOwner();
        final var fromId = addressIdConverter.convert(from);
        final var receiverId = addressIdConverter.convertCredit(to);
        return TransactionBody.newBuilder()
                .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                        .tokenTransfers(TokenTransferList.newBuilder()
                                .token(tokenId)
                                .nftTransfers(NftTransfer.newBuilder()
                                        .serialNumber(serialNo)
                                        .senderAccountID(fromId)
                                        .receiverAccountID(receiverId)
                                        .isApproval(!spenderId.equals(ownerId))
                                        .build())
                                .build()))
                .build();
    }

    @Nullable
    private AccountID getOwner() {
        final var nft = nativeOperations().getNft(tokenId, serialNo);
        final var token = nativeOperations().getToken(tokenId);
        return nft != null ? nft.ownerIdOrElse(token.treasuryAccountIdOrThrow()) : null;
    }
}

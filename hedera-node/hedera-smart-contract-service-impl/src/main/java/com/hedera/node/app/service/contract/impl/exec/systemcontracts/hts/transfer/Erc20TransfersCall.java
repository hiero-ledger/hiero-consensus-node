// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersCall.transferGasRequirement;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.Erc20TransfersTranslator.ERC_20_TRANSFER;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.Erc20TransfersTranslator.ERC_20_TRANSFER_FROM;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements the ERC-20 {@code transfer()} and {@code transferFrom()} calls of the HTS contract.
 */
public class Erc20TransfersCall extends AbstractCall {
    private final long amount;

    @Nullable
    private final Address from;

    private final Address to;

    @Nullable
    private final TokenID tokenId;

    private final VerificationStrategy verificationStrategy;
    private final AccountID senderId;
    private final AddressIdConverter addressIdConverter;
    private final boolean requiresApproval;
    private final SpecialRewardReceivers specialRewardReceivers;

    /**
     * @param gasCalculator the gas calculator used in this call
     * @param enhancement the enhancement used in this call
     * @param amount the amount of ERC20 tokens to be sent
     * @param from the address of the account from which the token will be transferred
     * @param to the address of the account to which the token will be transferred
     * @param tokenId the token id of the token to be transferred
     * @param verificationStrategy the verification strategy used in this call
     * @param senderId the sender id of the sending account
     * @param addressIdConverter the address ID converter for this call
     * @param requiresApproval whether the call requires an approval
     * @param specialRewardReceivers the special reward receiver
     */
    // too many parameters
    @SuppressWarnings("java:S107")
    public Erc20TransfersCall(
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            final long amount,
            @Nullable final Address from,
            @NonNull final Address to,
            @Nullable final TokenID tokenId,
            @NonNull final VerificationStrategy verificationStrategy,
            @NonNull final AccountID senderId,
            @NonNull final AddressIdConverter addressIdConverter,
            final boolean requiresApproval,
            @NonNull final SpecialRewardReceivers specialRewardReceivers) {
        super(gasCalculator, enhancement, false);
        this.amount = amount;
        this.from = from;
        this.to = requireNonNull(to);
        this.tokenId = tokenId;
        this.verificationStrategy = requireNonNull(verificationStrategy);
        this.senderId = requireNonNull(senderId);
        this.addressIdConverter = requireNonNull(addressIdConverter);
        this.requiresApproval = requiresApproval;
        this.specialRewardReceivers = requireNonNull(specialRewardReceivers);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        // https://eips.ethereum.org/EIPS/eip-20
        final var syntheticTransfer = syntheticTransferOrTransferFrom(senderId);
        final var gasRequirement = transferGasRequirement(syntheticTransfer, gasCalculator, enhancement, senderId);
        if (tokenId == null) {
            return reversionWith(INVALID_TOKEN_ID, gasRequirement);
        }
        final var recordBuilder = systemContractOperations()
                .dispatch(syntheticTransfer, verificationStrategy, senderId, ContractCallStreamBuilder.class);
        final var status = recordBuilder.status();
        if (status != SUCCESS) {
            if (status == NOT_SUPPORTED) {
                // matching behaviour of mono-service HTS system contract which directly enforced feature flags, ending
                // up doing an exceptional halt on NOT_SUPPORTED and consuming all gas
                return haltWith(gasRequirement, recordBuilder);
            } else {
                return gasOnly(revertResult(recordBuilder, gasRequirement), status, false);
            }
        } else {
            final var op = syntheticTransfer.cryptoTransferOrThrow();
            for (final var fungibleTransfers : op.tokenTransfers()) {
                TransferEventLoggingUtils.logSuccessfulFungibleTransfer(
                        requireNonNull(tokenId),
                        fungibleTransfers.transfers(),
                        enhancement.nativeOperations().readableAccountStore(),
                        frame);
            }
            specialRewardReceivers.addInFrame(frame, op, recordBuilder.getAssessedCustomFees());
            final var encodedOutput = (from == null)
                    ? ERC_20_TRANSFER.getOutputs().encode(Tuple.singleton(true))
                    : ERC_20_TRANSFER_FROM.getOutputs().encode(Tuple.singleton(true));
            final var outputData = Bytes.wrap(encodedOutput.array());
            recordBuilder
                    .contractCallResult(ContractFunctionResult.newBuilder()
                            .contractCallResult(outputData)
                            .build())
                    .evmCallTransactionResult(EvmTransactionResult.newBuilder()
                            .resultData(outputData)
                            .build());
            return gasOnly(successResult(encodedOutput, gasRequirement, recordBuilder), status, false);
        }
    }

    private TransactionBody syntheticTransferOrTransferFrom(@NonNull final AccountID spenderId) {
        final var receiverId = addressIdConverter.convertCredit(to);
        final var ownerId = (from == null) ? spenderId : addressIdConverter.convert(from);
        return TransactionBody.newBuilder()
                .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                        .tokenTransfers(TokenTransferList.newBuilder()
                                .token(tokenId)
                                .transfers(
                                        AccountAmount.newBuilder()
                                                .accountID(receiverId)
                                                .amount(amount)
                                                .build(),
                                        AccountAmount.newBuilder()
                                                .accountID(ownerId)
                                                .amount(-amount)
                                                .isApproval(requiresApproval || !spenderId.equals(ownerId))
                                                .build())
                                .build()))
                .build();
    }
}

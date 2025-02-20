// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements the ERC-20 {@code transfer()} and {@code transferFrom()} calls of the HTS contract.
 */
public class ERCGrantApprovalCall extends AbstractGrantApprovalCall {

    /**
     * @param enhancement the enhancement that is used
     * @param gasCalculator the gas calculator that is used
     * @param verificationStrategy the verification strategy that is used
     * @param senderId the sender id of the sending account
     * @param tokenId the token id of the token to be transferred
     * @param spenderId the spender id of the spending account
     * @param amount the amount that is approved
     * @param tokenType the token type of the token
     */
    // too many parameters
    @SuppressWarnings("java:S107")
    public ERCGrantApprovalCall(
            @NonNull final Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final VerificationStrategy verificationStrategy,
            @NonNull final AccountID senderId,
            @NonNull final TokenID tokenId,
            @NonNull final AccountID spenderId,
            final long amount,
            @NonNull final TokenType tokenType) {
        super(gasCalculator, enhancement, verificationStrategy, senderId, tokenId, spenderId, amount, tokenType, false);
    }

    @NonNull
    @Override
    public PricedResult execute(@NonNull final MessageFrame frame) {
        if (tokenId == null) {
            return reversionWith(INVALID_TOKEN_ID, gasCalculator.canonicalGasRequirement(DispatchType.APPROVE));
        }
        final var body = synthApprovalBody();
        final var recordBuilder = systemContractOperations()
                .dispatch(body, verificationStrategy, senderId, ContractCallStreamBuilder.class);
        final var status = withMonoStandard(recordBuilder).status();
        final var gasRequirement = gasCalculator.gasRequirement(body, DispatchType.APPROVE, senderId);
        if (status != SUCCESS) {
            return gasOnly(revertResult(recordBuilder, gasRequirement), status, false);
        } else {
            if (tokenType.equals(TokenType.NON_FUNGIBLE_UNIQUE)) {
                GrantApprovalLoggingUtils.logSuccessfulNFTApprove(
                        tokenId, senderId, spenderId, amount, readableAccountStore(), frame);
            } else {
                GrantApprovalLoggingUtils.logSuccessfulFTApprove(
                        tokenId, senderId, spenderId, amount, readableAccountStore(), frame);
            }
            final var encodedOutput = tokenType.equals(TokenType.FUNGIBLE_COMMON)
                    ? GrantApprovalTranslator.ERC_GRANT_APPROVAL.getOutputs().encode(Tuple.singleton(true))
                    : GrantApprovalTranslator.ERC_GRANT_APPROVAL_NFT
                            .getOutputs()
                            .encode(Tuple.EMPTY);
            return gasOnly(successResult(encodedOutput, gasRequirement, recordBuilder), status, false);
        }
    }
}

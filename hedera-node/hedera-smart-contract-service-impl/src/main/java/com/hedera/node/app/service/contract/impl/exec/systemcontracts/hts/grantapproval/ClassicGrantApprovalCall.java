// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.LogBuilder;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.log.Log;

/**
 * Implements the token redirect {@code approve()} call of the HTS system contract
 */
public class ClassicGrantApprovalCall extends AbstractGrantApprovalCall {

    // Keccak-256 hash of the event signature "Approval(address,address,uint256)".
    // Used as the topic0 identifier in Ethereum logs to detect Approval events.
    protected static final Bytes APPROVAL_EVENT =
            Bytes.fromHexString("8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925");

    /**
     * @param gasCalculator the gas calculator for the system contract
     * @param enhancement the enhancement to be used
     * @param verificationStrategy the verification strategy to use
     * @param senderId the account id of the sender
     * @param token the account id of the token
     * @param spender the account id of the spender
     * @param amount the amount to approve
     * @param tokenType the type of the token
     */
    // too many parameters
    @SuppressWarnings("java:S107")
    public ClassicGrantApprovalCall(
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final Enhancement enhancement,
            @NonNull final VerificationStrategy verificationStrategy,
            @NonNull final AccountID senderId,
            @NonNull final TokenID token,
            @NonNull final AccountID spender,
            final long amount,
            @NonNull final TokenType tokenType) {
        super(gasCalculator, enhancement, verificationStrategy, senderId, token, spender, amount, tokenType, false);
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
        final var status = recordBuilder.status();
        final var gasRequirement = gasCalculator.gasRequirement(body, DispatchType.APPROVE, senderId);
        if (status != SUCCESS) {
            return reversionWith(gasRequirement, recordBuilder);
        } else {
            final var tokenAddress = asLongZeroAddress(tokenId.tokenNum());
            if (tokenType.equals(TokenType.FUNGIBLE_COMMON)) {
                frame.addLog(getLogForFungibleAdjustAllowance(tokenAddress));
            } else {
                frame.addLog(getLogForNftAdjustAllowance(tokenAddress));
            }
            final var encodedOutput = tokenType.equals(TokenType.FUNGIBLE_COMMON)
                    ? GrantApprovalTranslator.GRANT_APPROVAL.getOutputs().encode(Tuple.of(status.protoOrdinal(), true))
                    : GrantApprovalTranslator.GRANT_APPROVAL_NFT.getOutputs().encode(Tuple.singleton((long)
                            status.protoOrdinal()));

            return gasOnly(successResult(encodedOutput, gasRequirement, recordBuilder), status, false);
        }
    }

    private Log getLogForFungibleAdjustAllowance(@NonNull final Address logger) {
        return LogBuilder.logBuilder()
                .forLogger(logger)
                .forEventSignature(APPROVAL_EVENT)
                .forIndexedArgument(asLongZeroAddress(senderId.accountNumOrThrow()))
                .forIndexedArgument(asLongZeroAddress(spenderId.accountNumOrThrow()))
                .forDataItem(amount)
                .build();
    }

    private Log getLogForNftAdjustAllowance(@NonNull final Address logger) {
        return LogBuilder.logBuilder()
                .forLogger(logger)
                .forEventSignature(APPROVAL_EVENT)
                .forIndexedArgument(asLongZeroAddress(senderId.accountNumOrThrow()))
                .forIndexedArgument(asLongZeroAddress(spenderId.accountNumOrThrow()))
                .forIndexedArgument(amount)
                .build();
    }
}

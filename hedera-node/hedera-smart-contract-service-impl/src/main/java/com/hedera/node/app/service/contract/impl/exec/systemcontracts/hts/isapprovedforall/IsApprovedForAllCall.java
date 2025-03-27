// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isapprovedforall;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isapprovedforall.IsApprovedForAllTranslator.CLASSIC_IS_APPROVED_FOR_ALL;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isapprovedforall.IsApprovedForAllTranslator.ERC_IS_APPROVED_FOR_ALL;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.accountNumberForEvmReference;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AbstractRevertibleTokenViewCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;

/**
 * Implements the token redirect {@code isApprovedForAll()} call of the HTS system contract.
 */
public class IsApprovedForAllCall extends AbstractRevertibleTokenViewCall {

    private final Address owner;
    private final Address operator;
    private final boolean isErcRedirect;

    public IsApprovedForAllCall(
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @Nullable final Token token,
            @NonNull final Address owner,
            @NonNull final Address operator,
            final boolean isErcRedirect) {
        super(gasCalculator, enhancement, token);
        this.owner = requireNonNull(owner);
        this.operator = requireNonNull(operator);
        this.isErcRedirect = isErcRedirect;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected @NonNull PricedResult resultOfViewingToken(@NonNull final Token token) {
        requireNonNull(token);
        if (token.tokenType() != TokenType.NON_FUNGIBLE_UNIQUE) {
            return gasOnly(revertResult(INVALID_TOKEN_ID, gasCalculator.viewGasRequirement()), INVALID_TOKEN_ID, true);
        }
        boolean verdict = false;
        final var ownerNum = accountNumberForEvmReference(owner, nativeOperations());
        final var operatorNum = accountNumberForEvmReference(operator, nativeOperations());

        if (operatorNum > 0 && ownerNum > 0) {
            verdict = operatorMatches(
                    requireNonNull(nativeOperations()
                            .getAccount(enhancement
                                    .nativeOperations()
                                    .entityIdFactory()
                                    .newAccountId(ownerNum))),
                    enhancement.nativeOperations().entityIdFactory().newAccountId(operatorNum),
                    token.tokenIdOrThrow());
        }
        if (isErcRedirect) {
            return gasOnly(
                    successResult(
                            ERC_IS_APPROVED_FOR_ALL.getOutputs().encode(Tuple.singleton(verdict)),
                            gasCalculator.viewGasRequirement()),
                    SUCCESS,
                    true);
        } else {
            return gasOnly(
                    successResult(
                            CLASSIC_IS_APPROVED_FOR_ALL
                                    .getOutputs()
                                    .encode(Tuple.of((long) SUCCESS.protoOrdinal(), verdict)),
                            gasCalculator.viewGasRequirement()),
                    SUCCESS,
                    true);
        }
    }

    private boolean operatorMatches(
            @NonNull final Account owner, @NonNull final AccountID operatorId, @NonNull final TokenID tokenId) {
        final var operatorApprovals =
                Optional.ofNullable(owner.approveForAllNftAllowances()).orElse(emptyList());
        for (final var approval : operatorApprovals) {
            if (tokenId.equals(approval.tokenIdOrThrow()) && operatorId.equals(approval.spenderIdOrThrow())) {
                return true;
            }
        }
        return false;
    }
}

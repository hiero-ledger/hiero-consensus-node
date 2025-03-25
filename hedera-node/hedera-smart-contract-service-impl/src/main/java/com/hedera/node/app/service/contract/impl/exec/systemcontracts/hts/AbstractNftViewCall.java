// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.ordinalRevertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.hapi.utils.HederaExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Implementation support for view calls that require an extant token and NFT to succeed.
 */
public abstract class AbstractNftViewCall extends AbstractRevertibleTokenViewCall {
    protected final long serialNo;

    protected AbstractNftViewCall(
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @Nullable final Token token,
            final long serialNo) {
        super(gasCalculator, enhancement, token);
        this.serialNo = serialNo;
    }

    @Override
    public @NonNull PricedResult execute() {
        if (token != null && token.tokenType() == TokenType.FUNGIBLE_COMMON) {
            // For backwards compatibility, we need to halt here per issue #8746.
            return gasOnly(
                    haltResult(
                            HederaExceptionalHaltReason.ERROR_DECODING_PRECOMPILE_INPUT,
                            gasCalculator.viewGasRequirement()),
                    INVALID_TOKEN_ID,
                    false);
        }
        return super.execute();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected @NonNull PricedResult resultOfViewingToken(@NonNull final Token token) {
        requireNonNull(token);
        final var nft = nativeOperations().getNft(token.tokenIdOrThrow(), serialNo);
        if (nft == null) {
            final var status = missingNftStatus();
            return gasOnly(ordinalRevertResult(status, gasCalculator.viewGasRequirement()), status, true);
        } else {
            return resultOfViewingNft(token, nft);
        }
    }

    /**
     * The status to return when the NFT is missing.
     *
     * @return the status to return when the NFT is missing
     */
    protected abstract ResponseCodeEnum missingNftStatus();

    /**
     * Returns the result of viewing the given NFT of the given token
     *
     * @param token the token
     * @param nft the NFT
     * @return the result of viewing the given NFT of the given token
     */
    @NonNull
    protected abstract PricedResult resultOfViewingNft(@NonNull Token token, @NonNull Nft nft);
}

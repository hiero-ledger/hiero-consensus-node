// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokenuri;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.TokenType.FUNGIBLE_COMMON;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.hapi.utils.HederaExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements the token redirect {@code tokenURI()} call of the HTS system contract.
 */
public class TokenUriCall extends AbstractCall {
    public static final String URI_QUERY_NON_EXISTING_TOKEN_ERROR = "ERC721Metadata: URI query for nonexistent token";

    private final long serialNo;

    @Nullable
    private final Token token;

    public TokenUriCall(
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @Nullable final Token token,
            final long serialNo) {
        super(gasCalculator, enhancement, true);
        this.token = token;
        this.serialNo = serialNo;
    }

    @Override
    public boolean allowsStaticFrame() {
        return true;
    }

    @Override
    public @NonNull PricedResult execute(MessageFrame frame) {
        var metadata = URI_QUERY_NON_EXISTING_TOKEN_ERROR;
        if (token != null) {
            if (token.tokenType() == FUNGIBLE_COMMON) {
                // For backwards compatibility, we need to halt here per issue #8746.
                return gasOnly(
                        haltResult(
                                HederaExceptionalHaltReason.ERROR_DECODING_PRECOMPILE_INPUT,
                                gasCalculator.viewGasRequirement()),
                        INVALID_TOKEN_ID,
                        false);
            }
            final var nft = nativeOperations().getNft(token.tokenIdOrThrow(), serialNo);
            if (nft != null) {
                metadata = new String(nft.metadata().toByteArray());
            }
        }
        return gasOnly(
                successResult(
                        TokenUriTranslator.TOKEN_URI.getOutputs().encode(Tuple.singleton(metadata)),
                        gasCalculator.viewGasRequirement()),
                SUCCESS,
                true);
    }
}

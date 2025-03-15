// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.nfttokeninfo;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.ZERO_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.TokenTupleUtils.nftTokenInfoTupleFor;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.nfttokeninfo.address_0x167.NftTokenInfoTranslator.NON_FUNGIBLE_TOKEN_INFO;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.nfttokeninfo.address_0x16c.NftTokenInfoTranslator.NON_FUNGIBLE_TOKEN_INFO_16C;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AbstractNonRevertibleTokenViewCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.config.data.LedgerConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.tuweni.bytes.Bytes;

public class NftTokenInfoCall extends AbstractNonRevertibleTokenViewCall {
    private final Configuration configuration;
    private final boolean isStaticCall;
    private final long serialNumber;
    private final Function function;

    public NftTokenInfoCall(
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            final boolean isStaticCall,
            @Nullable final Token token,
            final long serialNumber,
            @NonNull final Configuration configuration,
            Function function) {
        super(gasCalculator, enhancement, token);
        this.configuration = requireNonNull(configuration);
        this.serialNumber = serialNumber;
        this.isStaticCall = isStaticCall;
        this.function = function;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected @NonNull PricedResult resultOfViewingToken(@NonNull final Token token) {
        requireNonNull(token);
        final var nft = enhancement.nativeOperations().getNft(token.tokenIdOrElse(ZERO_TOKEN_ID), serialNumber);
        final var status = nft != null ? SUCCESS : INVALID_TOKEN_NFT_SERIAL_NUMBER;
        return gasOnly(fullResultsFor(status, gasCalculator.viewGasRequirement(), token, nft), status, true);
    }

    @Override
    protected @NonNull FullResult viewCallResultWith(
            @NonNull final ResponseCodeEnum status, final long gasRequirement) {
        return fullResultsFor(status, gasRequirement, Token.DEFAULT, null);
    }

    private @NonNull FullResult fullResultsFor(
            @NonNull final ResponseCodeEnum status,
            final long gasRequirement,
            @NonNull final Token token,
            @Nullable final Nft nft) {
        requireNonNull(status);
        requireNonNull(token);

        // For backwards compatibility, we need to revert here per issue #8746.
        if (isStaticCall && (status != SUCCESS || nft == null)) {
            return revertResult(status, gasCalculator.viewGasRequirement());
        }

        final var nonNullNft = nft != null ? nft : Nft.DEFAULT;
        final var ledgerConfig = configuration.getConfigData(LedgerConfig.class);
        final var ledgerId = Bytes.wrap(ledgerConfig.id().toByteArray()).toString();

        return function.getOutputs().equals(NON_FUNGIBLE_TOKEN_INFO.getOutputs())
                ? successResult(
                        NON_FUNGIBLE_TOKEN_INFO
                                .getOutputs()
                                .encode(Tuple.of(
                                        status.protoOrdinal(),
                                        nftTokenInfoTupleFor(
                                                token, nonNullNft, serialNumber, ledgerId, nativeOperations(), 1))),
                        gasRequirement)
                : successResult(
                        NON_FUNGIBLE_TOKEN_INFO_16C
                                .getOutputs()
                                .encode(Tuple.of(
                                        status.protoOrdinal(),
                                        nftTokenInfoTupleFor(
                                                token, nonNullNft, serialNumber, ledgerId, nativeOperations(), 2))),
                        gasRequirement);
    }
}

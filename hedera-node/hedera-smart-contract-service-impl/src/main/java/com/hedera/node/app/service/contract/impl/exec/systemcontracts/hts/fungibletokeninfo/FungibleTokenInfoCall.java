// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.fungibletokeninfo;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.TokenTupleUtils.fungibleTokenInfoTupleFor;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.fungibletokeninfo.address_0x167.FungibleTokenInfoTranslator.FUNGIBLE_TOKEN_INFO;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.fungibletokeninfo.address_0x16c.FungibleTokenInfoTranslator.FUNGIBLE_TOKEN_INFO_16C;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.ResponseCodeEnum;
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

public class FungibleTokenInfoCall extends AbstractNonRevertibleTokenViewCall {
    private final Configuration configuration;
    private final boolean isStaticCall;
    private final Function function;

    public FungibleTokenInfoCall(
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            final boolean isStaticCall,
            @Nullable final Token token,
            @NonNull final Configuration configuration,
            Function function) {
        super(gasCalculator, enhancement, token);
        this.configuration = requireNonNull(configuration);
        this.isStaticCall = isStaticCall;
        this.function = function;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected @NonNull PricedResult resultOfViewingToken(@NonNull final Token token) {
        requireNonNull(token);

        return gasOnly(fullResultsFor(SUCCESS, gasCalculator.viewGasRequirement(), token), SUCCESS, true);
    }

    @Override
    protected @NonNull FullResult viewCallResultWith(
            @NonNull final ResponseCodeEnum status, final long gasRequirement) {
        return fullResultsFor(status, gasRequirement, Token.DEFAULT);
    }

    private @NonNull FullResult fullResultsFor(
            @NonNull final ResponseCodeEnum status, final long gasRequirement, @NonNull final Token token) {
        requireNonNull(status);
        requireNonNull(token);

        final var ledgerConfig = configuration.getConfigData(LedgerConfig.class);
        final var ledgerId = Bytes.wrap(ledgerConfig.id().toByteArray()).toString();
        // For backwards compatibility, we need to revert here per issue #8746.
        if (isStaticCall && status != SUCCESS) {
            return revertResult(status, gasRequirement);
        }

        return function.getName().equals(FUNGIBLE_TOKEN_INFO.methodName())
                        && function.getOutputs().equals(FUNGIBLE_TOKEN_INFO.getOutputs())
                ? successResult(
                        FUNGIBLE_TOKEN_INFO
                                .getOutputs()
                                .encode(Tuple.of(status.protoOrdinal(), fungibleTokenInfoTupleFor(token, ledgerId, 1))),
                        gasRequirement)
                : successResult(
                        FUNGIBLE_TOKEN_INFO_16C
                                .getOutputs()
                                .encode(Tuple.of(status.protoOrdinal(), fungibleTokenInfoTupleFor(token, ledgerId, 2))),
                        gasRequirement);
    }
}

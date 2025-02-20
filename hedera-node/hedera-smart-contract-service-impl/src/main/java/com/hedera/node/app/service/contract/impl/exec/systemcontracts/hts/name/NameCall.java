// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.name;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AbstractRevertibleTokenViewCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;

/**
 * Implements the token redirect {@code name()} call of the HTS system contract.
 */
public class NameCall extends AbstractRevertibleTokenViewCall {

    public NameCall(
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @Nullable final Token token) {
        super(gasCalculator, enhancement, token);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected @NonNull PricedResult resultOfViewingToken(@NonNull Token token) {
        final var output = NameTranslator.NAME.getOutputs().encode(Tuple.singleton(token.name()));
        return gasOnly(successResult(output, gasCalculator.viewGasRequirement()), SUCCESS, true);
    }

    /**
     * Indicates if the given {@code selector} is a selector for {@link NameCall}.
     *
     * @param selector the selector to check
     * @return {@code true} if the given {@code selector} is a selector for {@link NameCall}
     */
    public static boolean matches(@NonNull final byte[] selector) {
        requireNonNull(selector);
        return Arrays.equals(selector, NameTranslator.NAME.selector());
    }

    /**
     * Constructs a {@link NameCall} from the given {@code attempt}.
     *
     * @param attempt the attempt to construct from
     * @return the constructed {@link NameCall}
     */
    public static NameCall from(@NonNull final HtsCallAttempt attempt) {
        return new NameCall(attempt.systemContractGasCalculator(), attempt.enhancement(), attempt.redirectToken());
    }
}

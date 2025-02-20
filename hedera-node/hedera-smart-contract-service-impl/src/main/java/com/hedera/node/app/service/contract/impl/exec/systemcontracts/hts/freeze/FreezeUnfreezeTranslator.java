// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.freeze;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall.FailureCustomizer.NOOP_CUSTOMIZER;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code freeze()}, {@code unfreeze()} calls to the HTS system contract.
 */
@Singleton
public class FreezeUnfreezeTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /** Selector for freezeToken(address,address) method. */
    public static final SystemContractMethod FREEZE = SystemContractMethod.declare(
                    "freezeToken(address,address)", ReturnTypes.INT_64)
            .withCategories(Category.FREEZE_UNFREEZE);
    /** Selector for unfreezeToken(address,address) method. */
    public static final SystemContractMethod UNFREEZE = SystemContractMethod.declare(
                    "unfreezeToken(address,address)", ReturnTypes.INT_64)
            .withCategories(Category.FREEZE_UNFREEZE);

    private final FreezeUnfreezeDecoder decoder;

    /**
     * @param decoder the decoder used for freeze/unfreeze calls
     */
    @Inject
    public FreezeUnfreezeTranslator(
            @NonNull final FreezeUnfreezeDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);
        this.decoder = decoder;

        registerMethods(FREEZE, UNFREEZE);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        return attempt.isMethod(FREEZE, UNFREEZE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        return new DispatchForResponseCodeHtsCall(
                attempt,
                bodyForClassic(attempt),
                attempt.isSelector(FREEZE)
                        ? FreezeUnfreezeTranslator::freezeGasRequirement
                        : FreezeUnfreezeTranslator::unfreezeGasRequirement,
                NOOP_CUSTOMIZER);
    }

    public static long freezeGasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.FREEZE, payerId);
    }

    public static long unfreezeGasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.UNFREEZE, payerId);
    }

    private TransactionBody bodyForClassic(@NonNull final HtsCallAttempt attempt) {
        if (Arrays.equals(attempt.selector(), FREEZE.selector())) {
            return decoder.decodeFreeze(attempt);
        } else {
            return decoder.decodeUnfreeze(attempt);
        }
    }
}

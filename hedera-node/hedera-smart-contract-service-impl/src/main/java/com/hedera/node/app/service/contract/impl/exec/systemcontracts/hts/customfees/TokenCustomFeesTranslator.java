// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.customfees;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.fromHeadlongAddress;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Modifier;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Variant;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class TokenCustomFeesTranslator extends AbstractCallTranslator<HtsCallAttempt> {

    public static final SystemContractMethod TOKEN_CUSTOM_FEES = SystemContractMethod.declare(
                    "getTokenCustomFees(address)", ReturnTypes.RESPONSE_CODE_CUSTOM_FEES)
            .withModifier(Modifier.VIEW)
            .withVariant(Variant.WITH_CUSTOM_FEES)
            .withCategory(Category.TOKEN_QUERY);

    @Inject
    public TokenCustomFeesTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(TOKEN_CUSTOM_FEES);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        return attempt.isMethod(TOKEN_CUSTOM_FEES);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        final var args = TOKEN_CUSTOM_FEES.decodeCall(attempt.input().toArrayUnsafe());
        final var token = attempt.linkedToken(fromHeadlongAddress(args.get(0)));
        return new TokenCustomFeesCall(
                attempt.systemContractGasCalculator(), attempt.enhancement(), attempt.isStaticCall(), token);
    }
}

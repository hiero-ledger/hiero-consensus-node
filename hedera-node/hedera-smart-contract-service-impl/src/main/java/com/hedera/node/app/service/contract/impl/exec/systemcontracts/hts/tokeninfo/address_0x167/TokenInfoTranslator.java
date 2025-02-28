// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokeninfo.address_0x167;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_167_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.fromHeadlongAddress;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokeninfo.TokenInfoCall;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;

public class TokenInfoTranslator extends AbstractCallTranslator<HtsCallAttempt> {

    /**
     * Selector for getTokenInfo(address) method.
     */
    public static final SystemContractMethod TOKEN_INFO_167 = SystemContractMethod.declare(
                    "getTokenInfo(address)", ReturnTypes.RESPONSE_CODE_TOKEN_INFO)
            .withModifier(SystemContractMethod.Modifier.VIEW)
            .withVariant(SystemContractMethod.Variant.V1)
            .withSupportedAddress(HTS_167_CONTRACT_ID)
            .withCategory(SystemContractMethod.Category.TOKEN_QUERY);

    /**
     * Default constructor for injection.
     */
    @Inject
    public TokenInfoTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(TOKEN_INFO_167);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);

        return attempt.isMethod(TOKEN_INFO_167);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        final var args = TOKEN_INFO_167.decodeCall(attempt.input().toArrayUnsafe());
        final var token = attempt.linkedToken(fromHeadlongAddress(args.get(0)));
        return new TokenInfoCall(
                attempt.systemContractGasCalculator(),
                attempt.enhancement(),
                attempt.isStaticCall(),
                token,
                attempt.configuration(),
                TOKEN_INFO_167.function());
    }
}

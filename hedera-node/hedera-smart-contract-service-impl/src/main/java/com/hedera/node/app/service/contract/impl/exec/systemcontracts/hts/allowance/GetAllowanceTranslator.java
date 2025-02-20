// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.allowance;

import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.CallVia;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Modifier;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code allowance()} calls to the HTS system contract.
 */
@Singleton
public class GetAllowanceTranslator extends AbstractCallTranslator<HtsCallAttempt> {

    /**
     * Selector for allowance(address,address,address) method.
     */
    public static final SystemContractMethod GET_ALLOWANCE = SystemContractMethod.declare(
                    "allowance(address,address,address)", ReturnTypes.RESPONSE_CODE_UINT256)
            .withModifier(Modifier.VIEW)
            .withCategories(Category.ALLOWANCE);
    /**
     * Selector for allowance(address,address) method.
     */
    public static final SystemContractMethod ERC_GET_ALLOWANCE = SystemContractMethod.declare(
                    "allowance(address,address)", ReturnTypes.UINT256)
            .withVia(CallVia.PROXY)
            .withModifier(Modifier.VIEW)
            .withCategories(Category.ALLOWANCE);

    /**
     * Default constructor for injection.
     */
    @Inject
    public GetAllowanceTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(GET_ALLOWANCE, ERC_GET_ALLOWANCE);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        return attempt.isMethod(GET_ALLOWANCE, ERC_GET_ALLOWANCE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        if (attempt.isSelector(ERC_GET_ALLOWANCE)) {
            final var call = GetAllowanceTranslator.ERC_GET_ALLOWANCE.decodeCall(attempt.inputBytes());
            return new GetAllowanceCall(
                    attempt.addressIdConverter(),
                    attempt.systemContractGasCalculator(),
                    attempt.enhancement(),
                    attempt.redirectToken(),
                    call.get(0),
                    call.get(1),
                    true,
                    attempt.isStaticCall());
        } else {
            final var call = GetAllowanceTranslator.GET_ALLOWANCE.decodeCall(attempt.inputBytes());
            final Address token = call.get(0);
            final Address owner = call.get(1);
            final Address spender = call.get(2);
            return new GetAllowanceCall(
                    attempt.addressIdConverter(),
                    attempt.systemContractGasCalculator(),
                    attempt.enhancement(),
                    attempt.linkedToken(ConversionUtils.fromHeadlongAddress(token)),
                    owner,
                    spender,
                    false,
                    attempt.isStaticCall());
        }
    }

    private boolean matchesErcSelector(@NonNull final byte[] selector) {
        return Arrays.equals(selector, ERC_GET_ALLOWANCE.selector());
    }
}

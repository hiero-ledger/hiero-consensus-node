// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.getapproved;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asExactLongValueOrZero;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.fromHeadlongAddress;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.CallVia;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Modifier;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code getApproved()} calls to the HTS system contract.
 */
@Singleton
public class GetApprovedTranslator extends AbstractCallTranslator<HtsCallAttempt> {

    /** Selector for getApproved(address,uint256) method. */
    public static final SystemContractMethod HAPI_GET_APPROVED = SystemContractMethod.declare(
                    "getApproved(address,uint256)", "(int32,address)")
            .withModifier(Modifier.VIEW)
            .withCategories(Category.TOKEN_QUERY, Category.APPROVAL);
    /** Selector for getApproved(uint256) method. */
    public static final SystemContractMethod ERC_GET_APPROVED = SystemContractMethod.declare(
                    "getApproved(uint256)", ReturnTypes.ADDRESS)
            .withVia(CallVia.PROXY)
            .withModifier(Modifier.VIEW)
            .withCategories(Category.ERC721, Category.TOKEN_QUERY, Category.APPROVAL);

    /**
     * Default constructor for injection.
     */
    @Inject
    public GetApprovedTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(HAPI_GET_APPROVED, ERC_GET_APPROVED);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);

        return attempt.isRedirect() ? attempt.isMethod(ERC_GET_APPROVED) : attempt.isMethod(HAPI_GET_APPROVED);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public GetApprovedCall callFrom(@NonNull final HtsCallAttempt attempt) {
        if (attempt.isSelector(ERC_GET_APPROVED)) {
            final var args = ERC_GET_APPROVED.decodeCall(attempt.input().toArrayUnsafe());
            return new GetApprovedCall(
                    attempt.systemContractGasCalculator(),
                    attempt.enhancement(),
                    attempt.redirectToken(),
                    asExactLongValueOrZero(args.get(0)),
                    true,
                    attempt.isStaticCall());
        } else {
            final var args = HAPI_GET_APPROVED.decodeCall(attempt.input().toArrayUnsafe());
            final var token = attempt.linkedToken(fromHeadlongAddress(args.get(0)));
            return new GetApprovedCall(
                    attempt.systemContractGasCalculator(),
                    attempt.enhancement(),
                    token,
                    asExactLongValueOrZero(args.get(1)),
                    false,
                    attempt.isStaticCall());
        }
    }
}

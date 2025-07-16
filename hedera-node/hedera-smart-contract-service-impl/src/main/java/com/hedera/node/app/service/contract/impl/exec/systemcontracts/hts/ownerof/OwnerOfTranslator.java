// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ownerof;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asExactLongValueOrZero;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Modifier;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code ownerOf()} calls to the HTS system contract.
 */
@Singleton
public class OwnerOfTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /** Selector for ownerOf(uint256) method. */
    public static final SystemContractMethod OWNER_OF = SystemContractMethod.declare(
                    "ownerOf(uint256)", ReturnTypes.ADDRESS)
            .withModifier(Modifier.VIEW)
            .withCategory(Category.TOKEN_QUERY);

    /**
     * Default constructor for injection.
     */
    @Inject
    public OwnerOfTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(OWNER_OF);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        return attempt.isMethod(OWNER_OF);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OwnerOfCall callFrom(@NonNull final HtsCallAttempt attempt) {
        // Since zero is never a valid serial number, if we clamp the passed value, the result
        // will be a revert with INVALID_TOKEN_NFT_SERIAL_NUMBER as reason
        final var serialNo = asExactLongValueOrZero(
                OWNER_OF.decodeCall(attempt.input().toArrayUnsafe()).get(0));
        return new OwnerOfCall(
                attempt.systemContractGasCalculator(), attempt.enhancement(), attempt.redirectToken(), serialNo);
    }
}

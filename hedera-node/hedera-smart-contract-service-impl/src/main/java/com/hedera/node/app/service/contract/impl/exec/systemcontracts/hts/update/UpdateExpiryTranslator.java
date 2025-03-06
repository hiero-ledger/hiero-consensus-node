// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update;

import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.EXPIRY;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.EXPIRY_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateCommonDecoder.FAILURE_CUSTOMIZER;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateDecoder;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Variant;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates ERC-721 {@code updateTokenExpiryInfo()} calls to the HTS system contract.
 */
@Singleton
public class UpdateExpiryTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /**
     * Selector for updateTokenExpiryInfo(address, EXPIRY) method.
     */
    public static final SystemContractMethod UPDATE_TOKEN_EXPIRY_INFO_V1 = SystemContractMethod.declare(
                    "updateTokenExpiryInfo(address," + EXPIRY + ")", ReturnTypes.INT)
            .withVariant(Variant.V1)
            .withCategories(Category.UPDATE);
    /**
     * Selector for updateTokenExpiryInfo(address, EXPIRY_V2) method.
     */
    public static final SystemContractMethod UPDATE_TOKEN_EXPIRY_INFO_V2 = SystemContractMethod.declare(
                    "updateTokenExpiryInfo(address," + EXPIRY_V2 + ")", ReturnTypes.INT)
            .withVariant(Variant.V2)
            .withCategories(Category.UPDATE);

    private final UpdateDecoder decoder;

    /**
     * @param decoder the decoder used for token update calls
     */
    @Inject
    public UpdateExpiryTranslator(
            UpdateDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);
        this.decoder = decoder;

        registerMethods(UPDATE_TOKEN_EXPIRY_INFO_V1, UPDATE_TOKEN_EXPIRY_INFO_V2);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        return attempt.isMethod(UPDATE_TOKEN_EXPIRY_INFO_V1, UPDATE_TOKEN_EXPIRY_INFO_V2);
    }

    @Override
    public Call callFrom(@NonNull HtsCallAttempt attempt) {
        return new DispatchForResponseCodeHtsCall(
                attempt, nominalBodyFor(attempt), UpdateCommons::gasRequirement, FAILURE_CUSTOMIZER);
    }

    private TransactionBody nominalBodyFor(@NonNull final HtsCallAttempt attempt) {
        if (Arrays.equals(attempt.selector(), UPDATE_TOKEN_EXPIRY_INFO_V1.selector())) {
            return decoder.decodeTokenUpdateExpiryV1(attempt);
        } else {
            return decoder.decodeTokenUpdateExpiryV2(attempt);
        }
    }
}

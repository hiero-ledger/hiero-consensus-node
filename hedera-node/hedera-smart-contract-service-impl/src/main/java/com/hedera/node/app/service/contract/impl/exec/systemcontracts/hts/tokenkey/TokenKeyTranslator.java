// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokenkey;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.fromHeadlongAddress;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.hapi.utils.InvalidTransactionException;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Modifier;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code getTokenKey()} calls to the HTS system contract.
 */
@Singleton
public class TokenKeyTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /** Selector for getTokenKey(address,uint) method. */
    public static final SystemContractMethod TOKEN_KEY = SystemContractMethod.declare(
                    "getTokenKey(address,uint)", ReturnTypes.RESPONSE_CODE_TOKEN_KEY)
            .withModifier(Modifier.VIEW)
            .withCategory(Category.TOKEN_QUERY);

    /**
     * Default constructor for injection.
     */
    @Inject
    public TokenKeyTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(TOKEN_KEY);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        return attempt.isMethod(TOKEN_KEY);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        final var args = TOKEN_KEY.decodeCall(attempt.input().toArrayUnsafe());
        final var token = attempt.linkedToken(fromHeadlongAddress(args.get(0)));
        final BigInteger keyType = args.get(1);

        final boolean metadataSupport =
                attempt.configuration().getConfigData(ContractsConfig.class).metadataKeyAndFieldEnabled();
        return new TokenKeyCall(
                attempt.systemContractGasCalculator(),
                attempt.enhancement(),
                attempt.isStaticCall(),
                token,
                getTokenKey(token, keyType.intValue(), metadataSupport));
    }

    public Key getTokenKey(final Token token, final int keyType, final boolean metadataSupport)
            throws InvalidTransactionException {
        if (token == null) {
            return null;
        }
        return switch (keyType) {
            case 1 -> token.adminKey();
            case 2 -> token.kycKey();
            case 4 -> token.freezeKey();
            case 8 -> token.wipeKey();
            case 16 -> token.supplyKey();
            case 32 -> token.feeScheduleKey();
            case 64 -> token.pauseKey();
            case 128 -> metadataSupport ? token.metadataKey() : null;
            default -> null;
        };
    }
}

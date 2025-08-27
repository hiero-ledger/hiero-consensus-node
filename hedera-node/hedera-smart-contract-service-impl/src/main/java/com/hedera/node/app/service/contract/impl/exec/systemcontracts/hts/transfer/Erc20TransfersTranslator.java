// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer;

import static com.hedera.hapi.node.base.TokenType.NON_FUNGIBLE_UNIQUE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.SpecialRewardReceivers.SPECIAL_REWARD_RECEIVERS;
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
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates ERC-20 transfer calls to the HTS system contract.
 */
@Singleton
public class Erc20TransfersTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /**
     * Selector for transfer(address,uint256) method.
     */
    public static final SystemContractMethod ERC_20_TRANSFER = SystemContractMethod.declare(
                    "transfer(address,uint256)", ReturnTypes.BOOL)
            .withVia(CallVia.PROXY)
            .withCategories(Category.ERC20, Category.TRANSFER);
    /**
     * Selector for transferFrom(address,address,uint256) method.
     */
    public static final SystemContractMethod ERC_20_TRANSFER_FROM = SystemContractMethod.declare(
                    "transferFrom(address,address,uint256)", ReturnTypes.BOOL)
            .withVia(CallVia.PROXY)
            .withCategories(Category.ERC20, Category.TRANSFER);

    /**
     * Default constructor for injection.
     */
    @Inject
    public Erc20TransfersTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(ERC_20_TRANSFER, ERC_20_TRANSFER_FROM);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        // Here, for ERC-20 == fungible tokens, we allow `transferFrom` (signature shared by ERC-20
        // and ERC-721) even if the token type doesn't exist.  (This is the case when `redirectTokenType()`
        // returns `null`.)
        if (!attempt.isRedirect()) return Optional.empty();
        if (attempt.redirectTokenType() == NON_FUNGIBLE_UNIQUE) return Optional.empty();
        return attempt.isMethod(ERC_20_TRANSFER, ERC_20_TRANSFER_FROM);
    }

    @Override
    public @Nullable Call callFrom(@NonNull final HtsCallAttempt attempt) {
        if (attempt.isSelector(ERC_20_TRANSFER)) {
            final var call = Erc20TransfersTranslator.ERC_20_TRANSFER.decodeCall(
                    attempt.input().toArrayUnsafe());
            return callFrom(null, call.get(0), call.get(1), attempt, false);
        } else {
            final var call = Erc20TransfersTranslator.ERC_20_TRANSFER_FROM.decodeCall(
                    attempt.input().toArrayUnsafe());
            return callFrom(call.get(0), call.get(1), call.get(2), attempt, true);
        }
    }

    private Erc20TransfersCall callFrom(
            @Nullable final Address from,
            @NonNull final Address to,
            @NonNull final BigInteger amount,
            @NonNull final HtsCallAttempt attempt,
            final boolean requiresApproval) {
        return new Erc20TransfersCall(
                attempt.systemContractGasCalculator(),
                attempt.enhancement(),
                amount.longValueExact(),
                from,
                to,
                requireNonNull(attempt.redirectToken()).tokenIdOrThrow(),
                attempt.defaultVerificationStrategy(),
                attempt.senderId(),
                attempt.addressIdConverter(),
                requiresApproval,
                SPECIAL_REWARD_RECEIVERS);
    }
}

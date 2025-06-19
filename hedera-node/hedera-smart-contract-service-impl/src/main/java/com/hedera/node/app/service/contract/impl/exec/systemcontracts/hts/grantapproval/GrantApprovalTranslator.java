// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval;

import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenType;
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
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.CallVia;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Variant;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code approve}, {@code approveNFT} calls to the HTS system contract.
 */
@Singleton
public class GrantApprovalTranslator extends AbstractCallTranslator<HtsCallAttempt> {

    /** Selector for approve(address,uint256) method. */
    public static final SystemContractMethod ERC_GRANT_APPROVAL = SystemContractMethod.declare(
                    "approve(address,uint256)", ReturnTypes.BOOL)
            .withVia(CallVia.PROXY)
            .withVariant(Variant.FT)
            .withCategories(Category.ERC20, Category.APPROVAL);
    /** Selector for approve(address,uint256) method. */
    public static final SystemContractMethod ERC_GRANT_APPROVAL_NFT = SystemContractMethod.declare(
                    "approve(address,uint256)")
            .withVia(CallVia.PROXY)
            .withVariant(Variant.NFT)
            .withCategories(Category.ERC721, Category.APPROVAL);
    /** Selector for approve(address,address,uint256) method. */
    public static final SystemContractMethod GRANT_APPROVAL = SystemContractMethod.declare(
                    "approve(address,address,uint256)", "(int32,bool)")
            .withVariant(Variant.FT)
            .withCategories(Category.ERC721, Category.APPROVAL);
    /** Selector for approveNFT(address,address,uint256) method. */
    public static final SystemContractMethod GRANT_APPROVAL_NFT = SystemContractMethod.declare(
                    "approveNFT(address,address,uint256)", ReturnTypes.INT_64)
            .withVariant(Variant.NFT)
            .withCategories(Category.APPROVAL);

    private final GrantApprovalDecoder decoder;

    /**
     * Constructor for injection.
     * @param decoder the decoder used to decode transfer calls
     */
    @Inject
    public GrantApprovalTranslator(
            @NonNull final GrantApprovalDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);
        this.decoder = decoder;

        registerMethods(ERC_GRANT_APPROVAL, ERC_GRANT_APPROVAL_NFT, GRANT_APPROVAL, GRANT_APPROVAL_NFT);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        return attempt.isMethod(GRANT_APPROVAL, GRANT_APPROVAL_NFT, ERC_GRANT_APPROVAL);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        if (attempt.isSelector(ERC_GRANT_APPROVAL)) {
            return bodyForErc(attempt);
        } else if (attempt.isSelector(GRANT_APPROVAL, GRANT_APPROVAL_NFT)) {
            return bodyForClassicCall(attempt);
        } else {
            return new DispatchForResponseCodeHtsCall(
                    attempt, bodyForClassic(attempt), GrantApprovalTranslator::gasRequirement);
        }
    }

    /**
     * @param body                          the transaction body to be dispatched
     * @param systemContractGasCalculator   the gas calculator for the system contract
     * @param enhancement                   the enhancement to use
     * @param payerId                       the payer of the transaction
     * @return the required gas
     */
    public static long gasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.APPROVE, payerId);
    }

    private TransactionBody bodyForClassic(final HtsCallAttempt attempt) {
        if (attempt.isSelector(GRANT_APPROVAL)) {
            return decoder.decodeGrantApproval(attempt);
        } else {
            return decoder.decodeGrantApprovalNFT(attempt);
        }
    }

    private ClassicGrantApprovalCall bodyForClassicCall(final HtsCallAttempt attempt) {
        final var isFungibleCall = attempt.isSelector(GRANT_APPROVAL);
        final var tokenType = isFungibleCall ? TokenType.FUNGIBLE_COMMON : TokenType.NON_FUNGIBLE_UNIQUE;
        final var call = isFungibleCall
                ? GrantApprovalTranslator.GRANT_APPROVAL.decodeCall(attempt.inputBytes())
                : GrantApprovalTranslator.GRANT_APPROVAL_NFT.decodeCall(attempt.inputBytes());
        final var tokenAddress = call.get(0);
        final var spender = attempt.addressIdConverter().convert(call.get(1));
        final var amount = exactLongFrom(call.get(2));
        return new ClassicGrantApprovalCall(
                attempt.systemContractGasCalculator(),
                attempt.enhancement(),
                attempt.defaultVerificationStrategy(),
                attempt.senderId(),
                ConversionUtils.asTokenId(
                        attempt.enhancement().nativeOperations().entityIdFactory(), (Address) tokenAddress),
                spender,
                amount,
                tokenType);
    }

    private ERCGrantApprovalCall bodyForErc(final HtsCallAttempt attempt) {
        final var call = GrantApprovalTranslator.ERC_GRANT_APPROVAL.decodeCall(attempt.inputBytes());
        final var spenderId = attempt.addressIdConverter().convert(call.get(0));
        final var amount = exactLongFrom(call.get(1));
        return new ERCGrantApprovalCall(
                attempt.enhancement(),
                attempt.systemContractGasCalculator(),
                attempt.defaultVerificationStrategy(),
                attempt.senderId(),
                requireNonNull(attempt.redirectTokenId()),
                spenderId,
                amount,
                requireNonNull(attempt.redirectTokenType()));
    }

    private long exactLongFrom(@NonNull final BigInteger value) {
        requireNonNull(value);
        return value.longValueExact();
    }
}

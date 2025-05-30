// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ApprovalSwitchHelper.APPROVAL_SWITCH_HELPER;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.CallStatusStandardizer.CALL_STATUS_STANDARDIZER;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.SpecialRewardReceivers.SPECIAL_REWARD_RECEIVERS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.SystemAccountCreditScreen.SYSTEM_ACCOUNT_CREDIT_SCREEN;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Variant;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates "classic" {@code cryptoTransfer()} calls to the HTS system contract.
 */
@Singleton
public class ClassicTransfersTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /**
     * Selector for cryptoTransfer((address,(address,int64)[],(address,address,int64)[])[]) method.
     */
    public static final SystemContractMethod CRYPTO_TRANSFER = SystemContractMethod.declare(
                    "cryptoTransfer((address,(address,int64)[],(address,address,int64)[])[])", ReturnTypes.INT_64)
            .withVariants(Variant.V1)
            .withCategories(Category.TRANSFER);
    /**
     * Selector for cryptoTransfer(((address,int64,bool)[]),(address,(address,int64,bool)[],(address,address,int64,bool)[])[]) method.
     */
    public static final SystemContractMethod CRYPTO_TRANSFER_V2 = SystemContractMethod.declare(
                    "cryptoTransfer(((address,int64,bool)[]),(address,(address,int64,bool)[],(address,address,int64,bool)[])[])",
                    ReturnTypes.INT_64)
            .withVariants(Variant.V2)
            .withCategories(Category.TRANSFER);
    /**
     * Selector for transferTokens(address,address[],int64[]) method.
     */
    public static final SystemContractMethod TRANSFER_TOKENS = SystemContractMethod.declare(
                    "transferTokens(address,address[],int64[])", ReturnTypes.INT_64)
            .withCategories(Category.TRANSFER);
    /**
     * Selector for transferToken(address,address,address,int64) method.
     */
    public static final SystemContractMethod TRANSFER_TOKEN = SystemContractMethod.declare(
                    "transferToken(address,address,address,int64)", ReturnTypes.INT_64)
            .withVariants(Variant.FT)
            .withCategories(Category.TRANSFER);
    /**
     * Selector for transferNFTs(address,address[],address[],int64[]) method.
     */
    public static final SystemContractMethod TRANSFER_NFTS = SystemContractMethod.declare(
                    "transferNFTs(address,address[],address[],int64[])", ReturnTypes.INT_64)
            .withVariants(Variant.NFT)
            .withCategories(Category.TRANSFER);
    /**
     * Selector for transferNFT(address,address,address,int64) method.
     */
    public static final SystemContractMethod TRANSFER_NFT = SystemContractMethod.declare(
                    "transferNFT(address,address,address,int64)", ReturnTypes.INT_64)
            .withVariants(Variant.NFT)
            .withCategories(Category.TRANSFER);
    /**
     * Selector for transferFrom(address,address,address,uint256) method.
     */
    public static final SystemContractMethod TRANSFER_FROM = SystemContractMethod.declare(
                    "transferFrom(address,address,address,uint256)", ReturnTypes.INT_64)
            .withVariants(Variant.FT)
            .withCategories(Category.TRANSFER);
    /**
     * Selector for transferFromNFT(address,address,address,uint256) method.
     */
    public static final SystemContractMethod TRANSFER_NFT_FROM = SystemContractMethod.declare(
                    "transferFromNFT(address,address,address,uint256)", ReturnTypes.INT_64)
            .withVariants(Variant.NFT)
            .withCategories(Category.TRANSFER);

    private final ClassicTransfersDecoder decoder;

    /**
     * Constructor for injection.
     * @param decoder the decoder used to decode transfer calls
     */
    @Inject
    public ClassicTransfersTranslator(
            ClassicTransfersDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);
        this.decoder = decoder;

        registerMethods(
                CRYPTO_TRANSFER,
                CRYPTO_TRANSFER_V2,
                TRANSFER_TOKENS,
                TRANSFER_TOKEN,
                TRANSFER_NFTS,
                TRANSFER_NFT,
                TRANSFER_FROM,
                TRANSFER_NFT_FROM);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        if (attempt.isRedirect()) return Optional.empty();
        return attempt.isMethod(
                CRYPTO_TRANSFER,
                CRYPTO_TRANSFER_V2,
                TRANSFER_TOKENS,
                TRANSFER_TOKEN,
                TRANSFER_NFTS,
                TRANSFER_NFT,
                TRANSFER_FROM,
                TRANSFER_NFT_FROM);
    }

    @Override
    public ClassicTransfersCall callFrom(@NonNull final HtsCallAttempt attempt) {
        final var selector = attempt.selector();
        return new ClassicTransfersCall(
                attempt.systemContractGasCalculator(),
                attempt.enhancement(),
                selector,
                // This is the only place we don't use the EVM sender id, because
                // we need to switch debits to approvals based on whether the
                // mono-service would have activated a key; and its key activation
                // test would use the qualified delegate id if applicable
                // test would use the qualified delegate id if applicable.
                // Only certain functions support qualified delegates, so restrict to those.
                isClassicCallSupportingQualifiedDelegate(attempt) ? attempt.authorizingId() : attempt.senderId(),
                decoder.checkForFailureStatus(attempt),
                nominalBodyFor(attempt),
                attempt.configuration(),
                isClassicCall(attempt) ? APPROVAL_SWITCH_HELPER : null,
                CALL_STATUS_STANDARDIZER,
                attempt.defaultVerificationStrategy(),
                SYSTEM_ACCOUNT_CREDIT_SCREEN,
                SPECIAL_REWARD_RECEIVERS);
    }

    private @Nullable TransactionBody nominalBodyFor(@NonNull final HtsCallAttempt attempt) {
        if (attempt.isSelector(CRYPTO_TRANSFER)) {
            return decoder.decodeCryptoTransfer(attempt.inputBytes(), attempt);
        } else if (attempt.isSelector(CRYPTO_TRANSFER_V2)) {
            return decoder.decodeCryptoTransferV2(attempt.inputBytes(), attempt);
        } else if (attempt.isSelector(TRANSFER_TOKENS)) {
            return decoder.decodeTransferTokens(attempt.inputBytes(), attempt);
        } else if (attempt.isSelector(TRANSFER_TOKEN)) {
            return decoder.decodeTransferToken(attempt.inputBytes(), attempt);
        } else if (attempt.isSelector(TRANSFER_NFTS)) {
            return decoder.decodeTransferNfts(attempt.inputBytes(), attempt);
        } else if (attempt.isSelector(TRANSFER_NFT)) {
            return decoder.decodeTransferNft(attempt.inputBytes(), attempt);
        } else if (attempt.isSelector(TRANSFER_FROM)) {
            return decoder.decodeHrcTransferFrom(attempt.inputBytes(), attempt);
        } else {
            return decoder.decodeHrcTransferNftFrom(attempt.inputBytes(), attempt);
        }
    }

    private boolean isClassicCall(@NonNull final HtsCallAttempt attempt) {
        return attempt.isMethod(
                        CRYPTO_TRANSFER,
                        CRYPTO_TRANSFER_V2,
                        TRANSFER_TOKENS,
                        TRANSFER_TOKEN,
                        TRANSFER_NFTS,
                        TRANSFER_NFT)
                .isPresent();
    }

    private boolean isClassicCallSupportingQualifiedDelegate(@NonNull final HtsCallAttempt attempt) {
        return attempt.isMethod(TRANSFER_TOKENS, TRANSFER_TOKEN, TRANSFER_NFTS, TRANSFER_NFT)
                .isPresent();
    }
}

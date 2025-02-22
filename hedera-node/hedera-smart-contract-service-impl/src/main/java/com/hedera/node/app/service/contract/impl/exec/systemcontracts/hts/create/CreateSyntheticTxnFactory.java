// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenSupplyType;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.token.TokenCreateTransactionBody.Builder;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenCreateWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenCreateWrapper.FixedFeeWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenCreateWrapper.FractionalFeeWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenCreateWrapper.RoyaltyFeeWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenKeyWrapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.inject.Singleton;

/**
 * A factory for creating a {@link TokenCreateTransactionBody.Builder}.
 */
@Singleton
public class CreateSyntheticTxnFactory {

    private CreateSyntheticTxnFactory() {
        // Singleton constructor
    }

    /**
     * @param tokenCreateWrapper the wrapper of token create transaction
     * @return the body of the token create transaction
     */
    @NonNull
    public static TokenCreateTransactionBody.Builder createToken(@NonNull final TokenCreateWrapper tokenCreateWrapper) {
        final var txnBodyBuilder = TokenCreateTransactionBody.newBuilder();
        txnBodyBuilder
                .name(tokenCreateWrapper.getName())
                .symbol(tokenCreateWrapper.getSymbol())
                .decimals(tokenCreateWrapper.getDecimals())
                .tokenType(tokenCreateWrapper.isFungible() ? TokenType.FUNGIBLE_COMMON : TokenType.NON_FUNGIBLE_UNIQUE)
                .supplyType(tokenCreateWrapper.isSupplyTypeFinite() ? TokenSupplyType.FINITE : TokenSupplyType.INFINITE)
                .maxSupply(tokenCreateWrapper.getMaxSupply())
                .initialSupply(tokenCreateWrapper.getInitSupply())
                .freezeDefault(tokenCreateWrapper.isFreezeDefault())
                .memo(tokenCreateWrapper.getMemo());

        // checks for treasury
        if (tokenCreateWrapper.getTreasury() != null) {
            txnBodyBuilder.treasury(tokenCreateWrapper.getTreasury());
        }

        // Set keys if they exist
        setTokenKeys(tokenCreateWrapper, txnBodyBuilder);

        // Set expiry details
        setExpiry(tokenCreateWrapper, txnBodyBuilder);

        // Add custom fees
        addCustomFees(tokenCreateWrapper, txnBodyBuilder);

        return txnBodyBuilder;
    }

    public static TokenCreateTransactionBody.Builder createTokenWithMetadata(
            @NonNull final TokenCreateWrapper tokenCreateWrapper) {
        final var transactionBodyBuilder = createToken(tokenCreateWrapper);
        setMetadata(tokenCreateWrapper, transactionBodyBuilder);
        setMetadataKey(tokenCreateWrapper, transactionBodyBuilder);
        return transactionBodyBuilder;
    }

    private static void setTokenKeys(
            @NonNull final TokenCreateWrapper tokenCreateWrapper, final Builder txnBodyBuilder) {
        tokenCreateWrapper.getTokenKeys().forEach(tokenKeyWrapper -> {
            final var key = tokenKeyWrapper.key().asGrpc();
            if (Key.DEFAULT.equals(key)) {
                throw new IllegalArgumentException();
            }
            if (tokenKeyWrapper.isUsedForAdminKey()) {
                txnBodyBuilder.adminKey(key);
            }
            if (tokenKeyWrapper.isUsedForKycKey()) {
                txnBodyBuilder.kycKey(key);
            }
            if (tokenKeyWrapper.isUsedForFreezeKey()) {
                txnBodyBuilder.freezeKey(key);
            }
            if (tokenKeyWrapper.isUsedForWipeKey()) {
                txnBodyBuilder.wipeKey(key);
            }
            if (tokenKeyWrapper.isUsedForSupplyKey()) {
                txnBodyBuilder.supplyKey(key);
            }
            if (tokenKeyWrapper.isUsedForFeeScheduleKey()) {
                txnBodyBuilder.feeScheduleKey(key);
            }
            if (tokenKeyWrapper.isUsedForPauseKey()) {
                txnBodyBuilder.pauseKey(key);
            }
        });
    }

    private static void setExpiry(
            @NonNull final TokenCreateWrapper tokenCreateWrapper, @NonNull final Builder txnBodyBuilder) {
        if (tokenCreateWrapper.getExpiry().second() != 0) {
            txnBodyBuilder.expiry(Timestamp.newBuilder()
                    .seconds(tokenCreateWrapper.getExpiry().second())
                    .build());
        }
        if (tokenCreateWrapper.getExpiry().autoRenewAccount() != null) {
            txnBodyBuilder.autoRenewAccount(tokenCreateWrapper.getExpiry().autoRenewAccount());
        }
        if (tokenCreateWrapper.getExpiry().autoRenewPeriod() != null) {
            txnBodyBuilder.autoRenewPeriod(tokenCreateWrapper.getExpiry().autoRenewPeriod());
        }
    }

    private static void setMetadataKey(
            @NonNull final TokenCreateWrapper tokenCreateWrapper, final Builder txnBodyBuilder) {
        tokenCreateWrapper.getTokenKeys().stream()
                .filter(TokenKeyWrapper::isUsedForMetadataKey)
                .map(tokenKeyWrapper -> tokenKeyWrapper.key().asGrpc())
                .forEach(txnBodyBuilder::metadataKey);
    }

    private static void setMetadata(
            @NonNull final TokenCreateWrapper tokenCreateWrapper, final Builder txnBodyBuilder) {
        if (tokenCreateWrapper.getMetadata() != null) {
            txnBodyBuilder.metadata(tokenCreateWrapper.getMetadata());
        }
    }

    private static void addCustomFees(
            @NonNull final TokenCreateWrapper tokenCreateWrapper, @NonNull final Builder txnBodyBuilder) {
        final var fractionalFees =
                tokenCreateWrapper.getFractionalFees().stream().map(FractionalFeeWrapper::asGrpc);
        final var fixedFees = tokenCreateWrapper.getFixedFees().stream().map(FixedFeeWrapper::asGrpc);
        final var royaltyFees = tokenCreateWrapper.getRoyaltyFees().stream().map(RoyaltyFeeWrapper::asGrpc);

        var allFees = Stream.of(fixedFees, fractionalFees, royaltyFees)
                .flatMap(Function.identity())
                .toList();

        if (!allFees.isEmpty()) {
            txnBodyBuilder.customFees(allFees);
        }
    }
}

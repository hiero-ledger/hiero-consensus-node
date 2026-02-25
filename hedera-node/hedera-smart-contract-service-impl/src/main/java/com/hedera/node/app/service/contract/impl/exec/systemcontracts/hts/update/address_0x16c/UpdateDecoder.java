// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x16c;

import com.esaulpaugh.headlong.abi.Tuple;
import com.google.common.primitives.Longs;
import com.hedera.hapi.node.token.TokenUpdateNftsTransactionBody;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateCommonDecoder;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenKeyWrapper;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class UpdateDecoder extends UpdateCommonDecoder {

    /**
     * Default constructor for injection.
     */
    @Inject
    public UpdateDecoder() {
        // Dagger2
    }

    /**
     * Decodes a call to {@link UpdateTranslator#TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA} into a synthetic {@link TransactionBody}.
     *
     * @param attempt the attempt
     * @return the synthetic transaction body
     */
    public TransactionBody decodeTokenUpdateWithMetadata(@NonNull final HtsCallAttempt attempt) {
        final var call = UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA.decodeCall(
                attempt.input().toArrayUnsafe());
        final var decoded = decodeUpdateWithMeta(call, attempt.addressIdConverter(), attempt.nativeOperations());
        return TransactionBody.newBuilder().tokenUpdate(decoded).build();
    }

    @Override
    protected Tuple decodeCall(@NonNull final HtsCallAttempt attempt) {
        return UpdateKeysTranslator.TOKEN_UPDATE_KEYS_16C.decodeCall(
                attempt.input().toArrayUnsafe());
    }

    public TokenUpdateTransactionBody.Builder decodeUpdateWithMeta(
            @NonNull final Tuple call,
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final HederaNativeOperations nativeOperation) {
        final var tokenUpdateTransactionBody = decodeTokenUpdate(call, addressIdConverter, nativeOperation);
        final var hederaToken = (Tuple) call.get(HEDERA_TOKEN);
        final Bytes tokenMetadata = hederaToken.size() > 9 ? Bytes.wrap((byte[]) hederaToken.get(9)) : null;
        if (tokenMetadata != null && tokenMetadata.length() > 0) {
            tokenUpdateTransactionBody.metadata(tokenMetadata);
        }
        final List<TokenKeyWrapper> tokenKeys =
                decodeTokenKeys(hederaToken.get(7), addressIdConverter, nativeOperation);
        addKeys(tokenKeys, tokenUpdateTransactionBody);
        addMetaKey(tokenKeys, tokenUpdateTransactionBody);
        return tokenUpdateTransactionBody;
    }

    public TransactionBody decodeUpdateNFTsMetadata(@NonNull final HtsCallAttempt attempt) {
        final var call = UpdateNFTsMetadataTranslator.UPDATE_NFTs_METADATA.decodeCall(
                attempt.input().toArrayUnsafe());

        final var tokenId =
                ConversionUtils.asTokenId(attempt.nativeOperations().entityIdFactory(), call.get(TOKEN_ADDRESS));
        final List<Long> serialNumbers = Longs.asList(call.get(SERIAL_NUMBERS));
        final byte[] metadata = call.get(METADATA);

        final var txnBodyBuilder = TokenUpdateNftsTransactionBody.newBuilder()
                .token(tokenId)
                .serialNumbers(serialNumbers)
                .metadata(Bytes.wrap(metadata));

        return TransactionBody.newBuilder().tokenUpdateNfts(txnBodyBuilder).build();
    }

    @Override
    @Nullable
    public TransactionBody decodeTokenUpdateKeys(@NonNull final HtsCallAttempt attempt) {

        final var call = decodeCall(attempt);

        final var tokenId =
                ConversionUtils.asTokenId(attempt.nativeOperations().entityIdFactory(), call.get(TOKEN_ADDRESS));
        final var tokenKeys =
                decodeTokenKeys(call.get(TOKEN_KEYS), attempt.addressIdConverter(), attempt.nativeOperations());

        // Build the transaction body
        final var txnBodyBuilder = TokenUpdateTransactionBody.newBuilder();
        txnBodyBuilder.token(tokenId);
        addKeys(tokenKeys, txnBodyBuilder);
        addMetaKey(tokenKeys, txnBodyBuilder);

        try {
            return TransactionBody.newBuilder().tokenUpdate(txnBodyBuilder).build();
        } catch (final IllegalArgumentException ignore) {
            return null;
        }
    }

    private void addMetaKey(final List<TokenKeyWrapper> tokenKeys, final TokenUpdateTransactionBody.Builder builder) {
        tokenKeys.forEach(tokenKeyWrapper -> {
            final var key = tokenKeyWrapper.key().asGrpc();
            if (tokenKeyWrapper.isUsedForMetadataKey()) {
                builder.metadataKey(key);
            }
        });
    }
}

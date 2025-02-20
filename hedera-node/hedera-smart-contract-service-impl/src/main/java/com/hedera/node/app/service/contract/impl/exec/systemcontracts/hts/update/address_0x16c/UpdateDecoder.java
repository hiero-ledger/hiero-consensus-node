/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x16c;

import com.esaulpaugh.headlong.abi.Tuple;
import com.google.common.primitives.Longs;
import com.hedera.hapi.node.token.TokenUpdateNftsTransactionBody;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateCommonDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateNFTsMetadataTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenKeyWrapper;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
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
        final var decoded = decodeUpdateWithMeta(call, attempt.addressIdConverter());
        return TransactionBody.newBuilder().tokenUpdate(decoded).build();
    }

    @Override
    protected Tuple decodeCall(@NonNull final HtsCallAttempt attempt) {
        return UpdateKeysTranslator.TOKEN_UPDATE_KEYS_FUNCTION.decodeCall(
                attempt.input().toArrayUnsafe());
    }

    public TokenUpdateTransactionBody.Builder decodeUpdateWithMeta(
            @NonNull final Tuple call, @NonNull final AddressIdConverter addressIdConverter) {
        final var tokenUpdateTransactionBody = decodeTokenUpdate(call, addressIdConverter);
        final var hederaToken = (Tuple) call.get(HEDERA_TOKEN);
        final Bytes tokenMetadata = hederaToken.size() > 9 ? Bytes.wrap((byte[]) hederaToken.get(9)) : null;
        if (tokenMetadata != null && tokenMetadata.length() > 0) {
            tokenUpdateTransactionBody.metadata(tokenMetadata);
        }
        final List<TokenKeyWrapper> tokenKeys = decodeTokenKeys(hederaToken.get(7), addressIdConverter);
        addKeys(tokenKeys, tokenUpdateTransactionBody);
        addMetaKey(tokenKeys, tokenUpdateTransactionBody);
        return tokenUpdateTransactionBody;
    }

    public TransactionBody decodeUpdateNFTsMetadata(@NonNull final HtsCallAttempt attempt) {
        final var call = UpdateNFTsMetadataTranslator.UPDATE_NFTs_METADATA.decodeCall(
                attempt.input().toArrayUnsafe());

        final var tokenId = ConversionUtils.asTokenId(call.get(TOKEN_ADDRESS));
        final List<Long> serialNumbers = Longs.asList(call.get(SERIAL_NUMBERS));
        final byte[] metadata = call.get(METADATA);

        final var txnBodyBuilder = TokenUpdateNftsTransactionBody.newBuilder()
                .token(tokenId)
                .serialNumbers(serialNumbers)
                .metadata(Bytes.wrap(metadata));

        return TransactionBody.newBuilder().tokenUpdateNfts(txnBodyBuilder).build();
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

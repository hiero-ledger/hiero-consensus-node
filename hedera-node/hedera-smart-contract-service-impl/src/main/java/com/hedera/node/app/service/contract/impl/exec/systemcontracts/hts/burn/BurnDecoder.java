// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn;

import static com.hedera.hapi.node.base.TokenType.NON_FUNGIBLE_UNIQUE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.INT64_INT64;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.explicitFromHeadlong;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZero;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.google.common.primitives.Longs;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.TokenBurnTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Provides help in decoding an {@link HtsCallAttempt} representing a burn call into a synthetic {@link TransactionBody}.
 */
@Singleton
public class BurnDecoder {
    private static final TupleType BURN_RESULT_ENCODER = TupleType.parse(INT64_INT64);
    /**
     * Dispatch for burn calls output.
     */
    public static final DispatchForResponseCodeHtsCall.OutputFn BURN_OUTPUT_FN =
            recordBuilder -> BURN_RESULT_ENCODER.encode(
                    Tuple.of((long) recordBuilder.status().protoOrdinal(), recordBuilder.getNewTotalSupply()));

    /**
     * Default constructor for injection.
     */
    @Inject
    public BurnDecoder() {
        // Dagger2
    }

    /**
     * @param attempt the HTS call attempt
     * @return the synthetic transaction body for burn v1
     */
    public TransactionBody decodeBurn(@NonNull final HtsCallAttempt attempt) {
        final var call = BurnTranslator.BURN_TOKEN_V1.decodeCall(attempt.inputBytes());
        return synthBurnBody(
                call.get(0), ((BigInteger) call.get(1)).longValueExact(), Longs.asList(call.get(2)), attempt);
    }

    /**
     * @param attempt the HTS call attempt
     * @return the synthetic transaction body for burn v2
     */
    public TransactionBody decodeBurnV2(@NonNull final HtsCallAttempt attempt) {
        final var call = BurnTranslator.BURN_TOKEN_V2.decodeCall(attempt.inputBytes());
        return synthBurnBody(call.get(0), call.get(1), Longs.asList(call.get(2)), attempt);
    }

    private TransactionBody synthBurnBody(
            @NonNull final Address tokenAddress,
            final long maybeAmount,
            @NonNull final List<Long> maybeSerialNos,
            @NonNull final HtsCallAttempt attempt) {
        Token maybeToken = null;
        long tokenNum = 0;
        // try to look up the token by address, if it is a long zero
        if (isLongZero(tokenAddress)) {
            final var explicit = explicitFromHeadlong(tokenAddress);

            maybeToken = attempt.linkedToken(asEvmAddress(numberOfLongZero(explicit)));
            tokenNum = numberOfLongZero(explicit);
        }

        final var isNonFungible = maybeToken != null && maybeToken.tokenType() == NON_FUNGIBLE_UNIQUE;
        final var tokenId = maybeToken != null
                ? maybeToken.tokenIdOrThrow()
                : TokenID.newBuilder().tokenNum(tokenNum).build();
        return TransactionBody.newBuilder()
                .tokenBurn(burn(tokenId, isNonFungible, maybeAmount, maybeSerialNos))
                .build();
    }

    private TokenBurnTransactionBody burn(
            @NonNull final TokenID tokenID,
            final boolean isNonFungible,
            final long amount,
            @NonNull final List<Long> serialNos) {
        final var builder = TokenBurnTransactionBody.newBuilder().token(tokenID);
        if (isNonFungible) {
            builder.serialNumbers(serialNos);
        } else {
            builder.amount(amount);
        }
        return builder.build();
    }
}

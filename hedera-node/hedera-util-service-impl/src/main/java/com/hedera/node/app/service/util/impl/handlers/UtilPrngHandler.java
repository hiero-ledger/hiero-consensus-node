// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util.impl.handlers;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SubType;
import com.hedera.node.app.service.util.impl.records.PrngStreamBuilder;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A {@link TransactionHandler} for handling {@link HederaFunctionality#UTIL_PRNG} transactions.
 *
 * <p>This transaction uses the n-3 running hash to generate a pseudo-random number. The n-3 running hash is updated
 * and maintained by the application, based on the record files generated based on preceding transactions. In this way,
 * the number is both essentially unpredictable and deterministic. While a given node *could* determine the n-3 running
 * hash that *will be*, after it has run the hashgraph, it will then not be able to craft and insert a transaction that
 * can take advantage of that information. So for all intents and purposes, the number is unpredictable.
 */
@Singleton
public class UtilPrngHandler implements TransactionHandler {
    private static final Logger log = LogManager.getLogger(UtilPrngHandler.class);
    private static final Bytes MISSING_N_MINUS_3_RUNNING_HASH = Bytes.wrap(new byte[48]);

    @Inject
    public UtilPrngHandler() {
        // Dagger2
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        // nothing to do
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        // Negative ranges are not allowed
        if (context.body().utilPrngOrThrow().range() < 0) {
            throw new PreCheckException(ResponseCodeEnum.INVALID_PRNG_RANGE);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Fees calculateFees(@NonNull FeeContext feeContext) {
        // Determine the fees. If the range is specified (i.e. it isn't 0), then we charge for an additional 4 bytes
        // (one integer), otherwise we don't charge for any additional bytes. Standard transaction usage has already
        // been determined and loaded into the calculator.
        final var range = feeContext.body().utilPrngOrThrow().range();
        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .addBytesPerTransaction(range > 0 ? Integer.BYTES : 0)
                .calculate();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handle(@NonNull final HandleContext context) {
        final var op = context.body().utilPrngOrThrow();
        final var range = op.range();

        // Get the n-3 running hash. It should never be possible to get an empty n-3 hash, and certainly not in
        // mainnet. The only way to get one is to handle a transaction immediately after genesis. Even then it is
        // probably not possible, and if we really wanted to defend against that, we could deterministically
        // pre-populate the initial running hashes. Or we can return all zeros. Either way is just as safe, so we'll
        // just return whatever it is we are given. If we *do* happen to get back null, treat as empty zeros.
        var pseudoRandomBytes = context.blockRecordInfo().prngSeed();
        if (pseudoRandomBytes == null || pseudoRandomBytes.length() == 0) {
            log.info("No n-3 record running hash available. Will use all zeros.");
            pseudoRandomBytes = MISSING_N_MINUS_3_RUNNING_HASH;
        }

        // If `range` is provided then generate a random number in the given range from the pseudoRandomBytes,
        // otherwise just use the full pseudoRandomBytes as the random number.
        final var recordBuilder = context.savepointStack().getBaseBuilder(PrngStreamBuilder.class);
        if (range > 0) {
            final var pseudoRandomNumber = randomNumFromBytes(pseudoRandomBytes, range);
            recordBuilder.entropyNumber(pseudoRandomNumber);
        } else {
            recordBuilder.entropyBytes(pseudoRandomBytes);
        }
    }

    /**
     * Generate a random number from the given bytes in the given range.
     * @param pseudoRandomBytes bytes to generate random number from
     * @param range range of the random number
     * @return random number
     */
    private int randomNumFromBytes(@NonNull final Bytes pseudoRandomBytes, final int range) {
        // Use the initial 4 bytes of the random number to extract the integer value. This might be a negative number,
        // which when used with the modulus operator will result in a negative number. To avoid this, we mask off the
        // high bit to make sure we always have a positive number for the mod operator.
        final var initialBitsValue =
                ByteBuffer.wrap(pseudoRandomBytes.toByteArray(), 0, 4).getInt();
        return (int) mod(initialBitsValue, range);
    }

    /**
     * Returns {@code dividend mod divisor}, a non-negative value less than {@code divisor}.
     * This differs from {@code dividend % divisor}, which might be negative.
     *
     * @throws ArithmeticException if {@code m <= 0}
     */
    public static long mod(long dividend, int divisor) {
        if (divisor <= 0) {
            throw new ArithmeticException("Modulus must be positive");
        }
        long result = dividend % divisor;
        return (result >= 0) ? result : result + divisor;
    }
}

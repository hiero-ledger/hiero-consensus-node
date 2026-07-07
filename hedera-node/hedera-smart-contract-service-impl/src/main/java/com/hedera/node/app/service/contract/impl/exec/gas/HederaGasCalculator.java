// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.gas;

import com.hedera.node.app.hapi.utils.ethereum.AccessListItem;
import com.hedera.node.app.hapi.utils.ethereum.CodeDelegation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

public interface HederaGasCalculator extends GasCalculator {

    /**
     * Calculate gas requirements of the transaction.
     * This method mirrors {{@link GasCalculator#transactionIntrinsicGasCost(Transaction, long)},
     * but does not require a full Transaction object and uses
     * {@link GasCalculator#transactionFloorCost(Bytes, long)} for `minimumGasUsed` calculation.
     *
     * @param payload          the payload of the transaction
     * @param isContractCreate is this call a 'contract creation'
     * @param accessLists      the accessList of the transaction
     * @param codeDelegations  the authorizationList of the type 4 transaction
     * @return The gas requirements of the transaction
     */
    GasCharges transactionGasRequirements(
            @NonNull Bytes payload,
            boolean isContractCreate,
            @Nullable List<AccessListItem> accessLists,
            @Nullable List<CodeDelegation> codeDelegations);

    /**
     * Allocation-free variant of {@link #transactionGasRequirements(Bytes, boolean, List, List)} that takes the
     * payload size and its zero-byte count directly. The intrinsic- and floor-gas formulas depend on the payload
     * only through these two numbers, so callers that already hold the call data (as a {@code byte[]} or a PBJ
     * {@link com.hedera.pbj.runtime.io.buffer.Bytes}) can avoid materializing a Tuweni copy on every invocation.
     *
     * @param payloadSize      the number of bytes in the transaction payload
     * @param payloadZeroBytes the number of zero bytes in the transaction payload
     * @param isContractCreate is this call a 'contract creation'
     * @param accessLists      the accessList of the transaction
     * @param codeDelegations  the authorizationList of the type 4 transaction
     * @return The gas requirements of the transaction
     */
    GasCharges transactionGasRequirements(
            int payloadSize,
            int payloadZeroBytes,
            boolean isContractCreate,
            @Nullable List<AccessListItem> accessLists,
            @Nullable List<CodeDelegation> codeDelegations);

    /**
     * Counts the zero bytes in the given PBJ payload without copying it to a {@code byte[]} or Tuweni {@link Bytes}.
     *
     * <p>This sits on the consensus gas-pricing hot path (EIP-7623 floor gas), so it reads the payload eight bytes
     * at a time via {@link com.hedera.pbj.runtime.io.buffer.Bytes#getLong(long)} and counts zero bytes with a
     * branch-free SWAR (SIMD-within-a-register) test, rather than issuing one bounds-checked
     * {@link com.hedera.pbj.runtime.io.buffer.Bytes#getByte(long)} per byte. For each 64-bit word the transform
     * {@code (((v & 0x7F..7F) + 0x7F..7F) | v) & 0x80..80} leaves the high bit of every <i>non-zero</i> byte set, so
     * {@code 8 - Long.bitCount(...)} is the number of zero bytes in that word. Byte order is irrelevant because each
     * byte is tested independently. The trailing {@code length % 8} bytes are handled with a scalar loop.
     *
     * @param payload the transaction payload
     * @return the number of zero bytes in the payload
     */
    static int payloadZeroBytes(@NonNull final com.hedera.pbj.runtime.io.buffer.Bytes payload) {
        final long length = payload.length();
        int zeros = 0;
        long i = 0;
        final long wordLimit = length - Long.BYTES + 1;
        for (; i < wordLimit; i += Long.BYTES) {
            final long v = payload.getLong(i);
            final long nonZeroFlags = (((v & 0x7F7F7F7F7F7F7F7FL) + 0x7F7F7F7F7F7F7F7FL) | v) & 0x8080808080808080L;
            zeros += Long.BYTES - Long.bitCount(nonZeroFlags);
        }
        for (; i < length; i++) {
            if (payload.getByte(i) == 0) {
                zeros++;
            }
        }
        return zeros;
    }
}

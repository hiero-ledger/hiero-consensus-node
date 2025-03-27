// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.utils;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;

public class RedirectBytecodeUtils {

    private static final String ADDRESS_BYTECODE_PATTERN = "fefefefefefefefefefefefefefefefefefefefe";

    public static final String PROXY_PRE_BYTES = "6080604052348015600f57600080fd5b50600061";
    public static final String PROXY_MID_BYTES = "905077";
    private static final String PROXY_POST_BYTES =
            ADDRESS_BYTECODE_PATTERN + "600052366000602037600080366018016008845af43d806000803e8160008114"
                    + "605857816000f35b816000fdfea2646970667358221220d8378feed472ba49a0"
                    + "005514ef7087017f707b45fb9bf56bb81bb93ff19a238b64736f6c634300080b0033";

    // The following hex string is created by compiling the contract defined in HIP-719.
    // (https://hips.hedera.com/hip/hip-719).  The only exception is that the function selector for `redirectForToken`
    // (0x618dc65e)
    // has been pre substituted before the ADDRESS_BYTECODE_PATTERN.
    private static final String TOKEN_CALL_REDIRECT_CONTRACT_BINARY = PROXY_PRE_BYTES
            + "0167" // System contract address for HTS
            + PROXY_MID_BYTES
            + "618dc65e" // function selector for `redirectForToken`
            + PROXY_POST_BYTES;

    // The following byte code is created by compiling the contract defined in HIP-906
    // (https://hips.hedera.com/hip/hip-906).  The only exception is that the function selector for `redirectForAccount`
    // (0xe4cbd3a7)
    // has been pre substituted before the ADDRESS_BYTECODE_PATTERN.
    private static final String ACCOUNT_CALL_REDIRECT_CONTRACT_BINARY = PROXY_PRE_BYTES
            + "016a" // System contract address for HAS
            + PROXY_MID_BYTES
            + "e4cbd3a7" // function selector for `redirectForAccount`
            + PROXY_POST_BYTES;

    // The following byte code is copied from the `redirectForToken` and `redirectForAccount` contract defined above.
    // The only exception is that the function selector for `redirectForScheduleTxn` (0x5c3889ca)
    // has been pre substituted before the ADDRESS_BYTECODE_PATTERN.
    private static final String SCHEDULE_CALL_REDIRECT_CONTRACT_BINARY = PROXY_PRE_BYTES
            + "016b" // System contract address for HSS
            + PROXY_MID_BYTES
            + "5c3889ca" // function selector for `redirectForScheduleTxn`
            + PROXY_POST_BYTES;

    private RedirectBytecodeUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    public static Bytes tokenProxyBytecodeFor(@NonNull final Address address) {
        requireNonNull(address);
        return Bytes.fromHexString(
                TOKEN_CALL_REDIRECT_CONTRACT_BINARY.replace(ADDRESS_BYTECODE_PATTERN, address.toUnprefixedHexString()));
    }

    public static com.hedera.pbj.runtime.io.buffer.Bytes tokenProxyBytecodePjb(@Nullable final Address address) {
        return address == null
                ? com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY
                : com.hedera.pbj.runtime.io.buffer.Bytes.fromHex(TOKEN_CALL_REDIRECT_CONTRACT_BINARY.replace(
                        ADDRESS_BYTECODE_PATTERN, address.toUnprefixedHexString()));
    }

    public static Bytes accountProxyBytecodeFor(@Nullable final Address address) {
        return address == null
                ? Bytes.EMPTY
                : Bytes.fromHexString(ACCOUNT_CALL_REDIRECT_CONTRACT_BINARY.replace(
                        ADDRESS_BYTECODE_PATTERN, address.toUnprefixedHexString()));
    }

    public static com.hedera.pbj.runtime.io.buffer.Bytes accountProxyBytecodePjb(@Nullable final Address address) {
        return address == null
                ? com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY
                : com.hedera.pbj.runtime.io.buffer.Bytes.fromHex(ACCOUNT_CALL_REDIRECT_CONTRACT_BINARY.replace(
                        ADDRESS_BYTECODE_PATTERN, address.toUnprefixedHexString()));
    }

    public static Bytes scheduleProxyBytecodeFor(@Nullable final Address address) {
        return address == null
                ? Bytes.EMPTY
                : Bytes.fromHexString(SCHEDULE_CALL_REDIRECT_CONTRACT_BINARY.replace(
                        ADDRESS_BYTECODE_PATTERN, address.toUnprefixedHexString()));
    }

    public static com.hedera.pbj.runtime.io.buffer.Bytes scheduleProxyBytecodePjb(@Nullable final Address address) {
        return address == null
                ? com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY
                : com.hedera.pbj.runtime.io.buffer.Bytes.fromHex(SCHEDULE_CALL_REDIRECT_CONTRACT_BINARY.replace(
                        ADDRESS_BYTECODE_PATTERN, address.toUnprefixedHexString()));
    }
}

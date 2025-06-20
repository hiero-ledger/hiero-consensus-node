// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.utils;

import static java.lang.System.arraycopy;

import com.google.common.primitives.Longs;
import com.hedera.hapi.node.base.AccountID;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.utility.CommonUtils;

/**
 * Provides utility methods for network admin operations.
 */
public final class NetworkAdminServiceUtil {
    private NetworkAdminServiceUtil() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Function that converts Account ID to Hexed EVM address.
     * @param accountId Account ID to be converted
     * @return Returns string hexed EVM address for the given account ID
     */
    @NonNull
    public static String asHexedEvmAddress(@NonNull final AccountID accountId) {
        return CommonUtils.hex(asEvmAddress(accountId.accountNum()));
    }

    @NonNull
    private static byte[] asEvmAddress(final long num) {
        final byte[] evmAddress = new byte[20];
        arraycopy(Longs.toByteArray(num), 0, evmAddress, 12, 8);
        return evmAddress;
    }
}

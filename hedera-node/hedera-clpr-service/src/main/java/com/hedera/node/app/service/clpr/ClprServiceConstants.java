// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.pbj.runtime.io.buffer.Bytes;

/**
 * Constants for the native CLPR service and system contract.
 */
public final class ClprServiceConstants {
    public static final long CLPR_SERVICE_ACCOUNT_NUM = 0x16eL;
    public static final String CLPR_EVM_ADDRESS = "0x16e";
    public static final Bytes CLPR_EVM_ADDRESS_BYTES = Bytes.fromHex("000000000000000000000000000000000000016e");
    public static final AccountID CLPR_SERVICE_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(CLPR_SERVICE_ACCOUNT_NUM).build();
    public static final ContractID CLPR_CONTRACT_ID =
            ContractID.newBuilder().contractNum(CLPR_SERVICE_ACCOUNT_NUM).build();

    /** CLPR state storage surcharge per byte ($0.00001), in tinycents. */
    public static final long CLPR_STATE_BYTE_FEE_TINYCENTS = 100_000L;

    private ClprServiceConstants() {
        throw new UnsupportedOperationException("Utility class");
    }
}

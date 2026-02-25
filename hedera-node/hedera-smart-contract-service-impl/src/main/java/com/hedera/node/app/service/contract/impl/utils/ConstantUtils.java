// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.utils;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Fraction;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.pbj.runtime.io.buffer.Bytes;

/**
 * Utility class that holds static entity IDs.
 */
public final class ConstantUtils {

    public static final byte[] ZERO_ADDRESS_BYTE_ARRAY = new byte[20];
    public static final Address ZERO_ADDRESS = Address.wrap("0x0000000000000000000000000000000000000000");
    // When no value is set for AccountID, ContractID or TokenId the return value is set to 0.
    public static final AccountID ZERO_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(0).build();
    public static final ContractID ZERO_CONTRACT_ID =
            ContractID.newBuilder().contractNum(0).build();
    public static final ContractID EVM_ZERO_CONTRACT_ID = ContractID.newBuilder()
            .evmAddress(Bytes.wrap(ZERO_ADDRESS_BYTE_ARRAY))
            .build();
    public static final TokenID ZERO_TOKEN_ID = TokenID.newBuilder().tokenNum(0).build();
    public static final Fraction ZERO_FRACTION = new Fraction(0, 1);
    public static final FixedFee ZERO_FIXED_FEE = new FixedFee(0, null);

    private ConstantUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }
}

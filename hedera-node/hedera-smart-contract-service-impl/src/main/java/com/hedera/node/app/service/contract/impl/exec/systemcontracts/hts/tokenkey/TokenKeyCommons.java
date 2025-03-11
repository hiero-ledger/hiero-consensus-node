// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokenkey;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_16C_CONTRACT_ID;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.hapi.utils.InvalidTransactionException;
import javax.inject.Singleton;

@Singleton
public class TokenKeyCommons {

    private TokenKeyCommons() {
        // Dagger2
    }

    public static Key getTokenKey(final Token token, final int keyType, final ContractID systemContractId)
            throws InvalidTransactionException {
        if (token == null) {
            return null;
        }
        return switch (keyType) {
            case 1 -> token.adminKey();
            case 2 -> token.kycKey();
            case 4 -> token.freezeKey();
            case 8 -> token.wipeKey();
            case 16 -> token.supplyKey();
            case 32 -> token.feeScheduleKey();
            case 64 -> token.pauseKey();
            case 128 -> systemContractId.equals(HTS_16C_CONTRACT_ID) ? token.metadataKey() : null;
            default -> null;
        };
    }
}

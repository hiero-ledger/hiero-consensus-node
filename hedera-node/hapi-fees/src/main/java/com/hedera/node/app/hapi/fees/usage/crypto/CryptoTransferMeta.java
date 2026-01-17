// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import com.hederahashgraph.api.proto.java.SubType;

public class CryptoTransferMeta {
    private int tokenMultiplier = 1;

    private final int numTokensInvolved;
    private final int numFungibleTokenTransfers;
    private final int numNftOwnershipChanges;

    private int customFeeTokensInvolved;
    private int customFeeHbarTransfers;
    private int customFeeTokenTransfers;
    private int numHookInvocations;
    private boolean usesHooks;

    public CryptoTransferMeta(
            int tokenMultiplier,
            int numTokensInvolved,
            int numFungibleTokenTransfers,
            int numNftOwnershipChanges,
            final boolean usesHooks) {
        this.tokenMultiplier = tokenMultiplier;
        this.numTokensInvolved = numTokensInvolved;
        this.numFungibleTokenTransfers = numFungibleTokenTransfers;
        this.numNftOwnershipChanges = numNftOwnershipChanges;
        this.usesHooks = usesHooks;
    }

    public int getTokenMultiplier() {
        return tokenMultiplier;
    }

    public int getNumTokensInvolved() {
        return numTokensInvolved;
    }

    public int getNumFungibleTokenTransfers() {
        return numFungibleTokenTransfers;
    }

    public void setTokenMultiplier(int tokenMultiplier) {
        this.tokenMultiplier = tokenMultiplier;
    }

    public void setCustomFeeTokensInvolved(final int customFeeTokensInvolved) {
        this.customFeeTokensInvolved = customFeeTokensInvolved;
    }

    public int getCustomFeeTokensInvolved() {
        return customFeeTokensInvolved;
    }

    public void setCustomFeeTokenTransfers(final int customFeeTokenTransfers) {
        this.customFeeTokenTransfers = customFeeTokenTransfers;
    }

    public int getCustomFeeTokenTransfers() {
        return customFeeTokenTransfers;
    }

    public void setCustomFeeHbarTransfers(final int customFeeHbarTransfers) {
        this.customFeeHbarTransfers = customFeeHbarTransfers;
    }

    public int getCustomFeeHbarTransfers() {
        return customFeeHbarTransfers;
    }

    public int getNumNftOwnershipChanges() {
        return numNftOwnershipChanges;
    }

    public int getNumHookInvocations() {
        return numHookInvocations;
    }

    public void setNumHookInvocations(final int numHookInvocations) {
        this.numHookInvocations = numHookInvocations;
    }

    public SubType getSubType() {
        if (usesHooks) {
            return SubType.CRYPTO_TRANSFER_WITH_HOOKS;
        }
        if (numNftOwnershipChanges != 0) {
            if (customFeeHbarTransfers > 0 || customFeeTokenTransfers > 0) {
                return SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;
            }
            return SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
        }
        if (numFungibleTokenTransfers != 0) {
            if (customFeeHbarTransfers > 0 || customFeeTokenTransfers > 0) {
                return SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
            }
            return SubType.TOKEN_FUNGIBLE_COMMON;
        }
        return SubType.DEFAULT;
    }
}

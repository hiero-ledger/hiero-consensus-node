// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.gas;

import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * Enumerates the types of child transactions that can be dispatched to the HTS system contract.
 */
public enum DispatchType {
    /**
     * Dispatch for Hedera crypto create functionality with default resource prices.
     */
    CRYPTO_CREATE(HederaFunctionality.CRYPTO_CREATE, DEFAULT),
    /**
     * Dispatch for Hedera crypto update functionality with default resource prices.
     */
    CRYPTO_UPDATE(HederaFunctionality.CRYPTO_UPDATE, DEFAULT),
    /**
     * Dispatch for Hedera Ethereum transaction functionality with default resource prices.
     */
    ETHEREUM_TRANSACTION(HederaFunctionality.ETHEREUM_TRANSACTION, DEFAULT),
    /**
     * Dispatch for Hedera crypto transfer functionality with default resource prices.
     */
    TRANSFER_HBAR(HederaFunctionality.CRYPTO_TRANSFER, DEFAULT),
    /**
     * Dispatch for Hedera crypto transfer functionality with resource prices on a fungible token.
     */
    TRANSFER_FUNGIBLE(HederaFunctionality.CRYPTO_TRANSFER, TOKEN_FUNGIBLE_COMMON),
    /**
     * Dispatch for Hedera crypto transfer functionality with resource prices on a non-fungible token.
     */
    TRANSFER_NFT(HederaFunctionality.CRYPTO_TRANSFER, TOKEN_NON_FUNGIBLE_UNIQUE),
    /**
     * Dispatch for Hedera crypto transfer functionality with resource prices on a fungible token with custom fees.
     */
    TRANSFER_FUNGIBLE_CUSTOM_FEES(HederaFunctionality.CRYPTO_TRANSFER, TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES),
    /**
     * Dispatch for Hedera crypto transfer functionality with resource prices on a non-fungible token with custom fees.
     */
    TRANSFER_NFT_CUSTOM_FEES(HederaFunctionality.CRYPTO_TRANSFER, TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES),
    /**
     * Dispatch for Hedera token mint functionality with resource prices on a fungible token.
     */
    MINT_FUNGIBLE(HederaFunctionality.TOKEN_MINT, TOKEN_FUNGIBLE_COMMON),
    /**
     * Dispatch for Hedera token mint functionality with resource prices on non-fungible token.
     */
    MINT_NFT(HederaFunctionality.TOKEN_MINT, TOKEN_NON_FUNGIBLE_UNIQUE),
    /**
     * Dispatch for Hedera token burn functionality with resource prices on a fungible token.
     */
    BURN_FUNGIBLE(HederaFunctionality.TOKEN_BURN, TOKEN_FUNGIBLE_COMMON),
    /**
     * Dispatch for Hedera token delete functionality with default resource prices.
     */
    DELETE(HederaFunctionality.TOKEN_DELETE, DEFAULT),
    /**
     * Dispatch for Hedera token burn functionality with resource prices on a non-fungible token.
     */
    BURN_NFT(HederaFunctionality.TOKEN_BURN, TOKEN_NON_FUNGIBLE_UNIQUE),
    /**
     * Dispatch for Hedera associate token to account functionality with default resource prices.
     */
    ASSOCIATE(HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT, DEFAULT),
    /**
     * Dispatch for Hedera disassociate token to account functionality with default resource prices.
     */
    DISSOCIATE(HederaFunctionality.TOKEN_DISSOCIATE_FROM_ACCOUNT, DEFAULT),
    /**
     * Dispatch for Hedera approve allowance functionality with default resource prices.
     */
    APPROVE(HederaFunctionality.CRYPTO_APPROVE_ALLOWANCE, DEFAULT),
    /**
     * Dispatch for Hedera delete allowance functionality with default resource prices.
     */
    DELETE_NFT_APPROVE(HederaFunctionality.CRYPTO_DELETE_ALLOWANCE, DEFAULT),
    /**
     * Dispatch for Hedera token grant kyc to account functionality with default resource prices.
     */
    GRANT_KYC(HederaFunctionality.TOKEN_GRANT_KYC_TO_ACCOUNT, DEFAULT),
    /**
     * Dispatch for Hedera token revoke kyc from account functionality with default resource prices.
     */
    REVOKE_KYC(HederaFunctionality.TOKEN_REVOKE_KYC_FROM_ACCOUNT, DEFAULT),
    /**
     * Dispatch for Hedera token pause functionality with default resource prices.
     */
    PAUSE(HederaFunctionality.TOKEN_PAUSE, DEFAULT),
    /**
     * Dispatch for Hedera token unpause functionality with default resource prices.
     */
    UNPAUSE(HederaFunctionality.TOKEN_UNPAUSE, DEFAULT),
    /**
     * Dispatch for Hedera token freeze account functionality with default resource prices.
     */
    FREEZE(HederaFunctionality.TOKEN_FREEZE_ACCOUNT, DEFAULT),
    /**
     * Dispatch for Hedera token unfreeze account functionality with default resource prices.
     */
    UNFREEZE(HederaFunctionality.TOKEN_UNFREEZE_ACCOUNT, DEFAULT),
    /**
     * Dispatch for Hedera token wipe account functionality with resource prices on a fungible token.
     */
    WIPE_FUNGIBLE(HederaFunctionality.TOKEN_ACCOUNT_WIPE, TOKEN_FUNGIBLE_COMMON),
    /**
     * Dispatch for Hedera token wipe account functionality with resource prices on a non-fungible token.
     */
    WIPE_NFT(HederaFunctionality.TOKEN_ACCOUNT_WIPE, TOKEN_NON_FUNGIBLE_UNIQUE),
    /**
     * Dispatch for Hedera token update functionality with default resource prices.
     */
    UPDATE(HederaFunctionality.TOKEN_UPDATE, DEFAULT),
    /**
     * Dispatch for Hedera token update NFTs functionality with default resource prices.
     */
    TOKEN_UPDATE_NFTS(HederaFunctionality.TOKEN_UPDATE_NFTS, DEFAULT),
    /**
     * Dispatch for Hedera pseudorandom number generation functionality with default resource prices.
     */
    UTIL_PRNG(HederaFunctionality.UTIL_PRNG, DEFAULT),
    /**
     * Dispatch for Hedera get info of a token functionality with default resource prices.
     */
    TOKEN_INFO(HederaFunctionality.TOKEN_GET_INFO, DEFAULT),
    /**
     * Dispatch for Hedera update token fee schedule functionality with default resource prices.
     */
    UPDATE_TOKEN_CUSTOM_FEES(HederaFunctionality.TOKEN_FEE_SCHEDULE_UPDATE, DEFAULT),
    /**
     * Dispatch for Hedera token airdrop functionality with default resource prices.
     */
    TOKEN_AIRDROP(HederaFunctionality.TOKEN_AIRDROP, DEFAULT),
    /**
     * Dispatch for Hedera token claim airdrop functionality with default resource prices.
     */
    TOKEN_CLAIM_AIRDROP(HederaFunctionality.TOKEN_CLAIM_AIRDROP, DEFAULT),
    /**
     * Dispatch for Hedera token cancel airdrop functionality with default resource prices.
     */
    TOKEN_CANCEL_AIRDROP(HederaFunctionality.TOKEN_CANCEL_AIRDROP, DEFAULT),
    /**
     * Dispatch for Hedera token reject functionality with resource prices on a fungible token.
     */
    TOKEN_REJECT_FT(HederaFunctionality.TOKEN_REJECT, TOKEN_FUNGIBLE_COMMON),
    /**
     * Dispatch for Hedera token reject functionality with resource prices on a non-fungible token.
     */
    TOKEN_REJECT_NFT(HederaFunctionality.TOKEN_REJECT, TOKEN_NON_FUNGIBLE_UNIQUE),
    /**
     * Dispatch for Hedera schedule sign functionality with default resource prices.
     */
    SCHEDULE_SIGN(HederaFunctionality.SCHEDULE_SIGN, DEFAULT),
    /**
     * Dispatch for Hedera schedule create functionality with default resource prices.
     */
    SCHEDULE_CREATE(HederaFunctionality.SCHEDULE_CREATE, DEFAULT),
    /**
     * Dispatch for Hedera schedule create functionality with schedule create contract call resource prices.
     */
    SCHEDULE_CREATE_CONTRACT_CALL(HederaFunctionality.SCHEDULE_CREATE, SubType.SCHEDULE_CREATE_CONTRACT_CALL),
    /**
     * Dispatch for Hedera schedule delete functionality with default resource prices.
     */
    SCHEDULE_DELETE(HederaFunctionality.SCHEDULE_DELETE, DEFAULT),
    /**
     * Dispatch for Hedera schedule get info functionality with default resource prices.
     * Currently decided to use 'gasCalculator.viewGasRequirement()' for all view functions, so this one is not used.
     */
    SCHEDULE_GET_INFO(HederaFunctionality.SCHEDULE_GET_INFO, DEFAULT);

    private final HederaFunctionality functionality;
    private final SubType subtype;

    DispatchType(@NonNull final HederaFunctionality functionality, @NonNull final SubType subtype) {
        this.functionality = Objects.requireNonNull(functionality);
        this.subtype = Objects.requireNonNull(subtype);
    }

    /**
     * @return the Hedera functionality of the {@link DispatchType}.
     */
    public HederaFunctionality functionality() {
        return functionality;
    }

    /**
     * @return the subtype of the {@link DispatchType}.
     */
    public SubType subtype() {
        return subtype;
    }
}

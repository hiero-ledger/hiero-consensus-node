// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_CREATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_DELETE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_GET_TOPIC_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_UPDATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_GET_BYTECODE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_GET_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_APPROVE_ALLOWANCE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_DELETE_ALLOWANCE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_APPEND;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_GET_CONTENTS;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_GET_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_GET_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_SIGN;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ACCOUNT_WIPE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_AIRDROP;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_BURN;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CANCEL_AIRDROP;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CLAIM_AIRDROP;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_DISSOCIATE_FROM_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_FREEZE_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_GET_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_GRANT_KYC_TO_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_MINT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_PAUSE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_REJECT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_REVOKE_KYC_FROM_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UNFREEZE_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UNPAUSE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UPDATE_NFTS;

import com.hedera.hapi.node.base.HederaFunctionality;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hiero.hapi.fees.apis.common.BaseFeeModel;

/**
 * Registry of all fee models in the system. Internal admin only transactions
 * are currently omitted.
 */
public class FeeModelRegistry {
    private static final Map<HederaFunctionality, FeeModel> registry = new LinkedHashMap<>();

    static {
        registry.put(CONSENSUS_CREATE_TOPIC, new BaseFeeModel(CONSENSUS_CREATE_TOPIC, "Create a new topic"));
        registry.put(CONSENSUS_UPDATE_TOPIC, new BaseFeeModel(CONSENSUS_UPDATE_TOPIC, "Update topic"));
        registry.put(CONSENSUS_DELETE_TOPIC, new BaseFeeModel(CONSENSUS_DELETE_TOPIC, "Delete topic"));
        registry.put(CONSENSUS_GET_TOPIC_INFO, new BaseFeeModel(CONSENSUS_GET_TOPIC_INFO, "Get metadata for a topic"));
        registry.put(CONSENSUS_SUBMIT_MESSAGE, new BaseFeeModel(CONSENSUS_SUBMIT_MESSAGE, "Submit message to topic"));

        registry.put(FILE_CREATE, new BaseFeeModel(FILE_CREATE, "Create file"));
        registry.put(FILE_APPEND, new BaseFeeModel(FILE_APPEND, "Append to file"));
        registry.put(FILE_UPDATE, new BaseFeeModel(FILE_UPDATE, "Update file"));
        registry.put(FILE_DELETE, new BaseFeeModel(FILE_DELETE, "Delete file"));
        registry.put(FILE_GET_CONTENTS, new BaseFeeModel(FILE_GET_CONTENTS, "Get file contents"));
        registry.put(FILE_GET_INFO, new BaseFeeModel(FILE_GET_INFO, "Get file info"));

        registry.put(CRYPTO_TRANSFER, new BaseFeeModel(CRYPTO_TRANSFER, "Transfer tokens among accounts"));
        registry.put(CRYPTO_UPDATE, new BaseFeeModel(CRYPTO_UPDATE, "Update an account"));
        registry.put(CRYPTO_DELETE, new BaseFeeModel(CRYPTO_DELETE, "Delete an account"));
        registry.put(CRYPTO_CREATE, new BaseFeeModel(CRYPTO_CREATE, "Create a new account"));
        registry.put(CRYPTO_APPROVE_ALLOWANCE, new BaseFeeModel(CRYPTO_APPROVE_ALLOWANCE, "Approve an allowance for a spender"));
        registry.put(CRYPTO_DELETE_ALLOWANCE, new BaseFeeModel(CRYPTO_DELETE_ALLOWANCE, "Delete an allowance for a spender"));

        registry.put(CONTRACT_CALL, new BaseFeeModel(CONTRACT_CALL, "Execute a smart contract call"));
        registry.put(CONTRACT_CREATE, new BaseFeeModel(CONTRACT_CREATE, "Create a smart contract"));
        registry.put(CONTRACT_UPDATE, new BaseFeeModel(CONTRACT_UPDATE, "Update a smart contract"));
        registry.put(CONTRACT_GET_INFO, new BaseFeeModel(CONTRACT_GET_INFO, "Get information about a smart contract"));
        registry.put(CONTRACT_GET_BYTECODE, new BaseFeeModel(CONTRACT_GET_BYTECODE, "Get the compiled bytecode for a smart contract"));
        registry.put(CONTRACT_DELETE, new BaseFeeModel(CONTRACT_DELETE, "Delete a smart contract"));

        registry.put(TOKEN_CREATE, new BaseFeeModel(TOKEN_CREATE, "Create a token"));
        registry.put(TOKEN_GET_INFO, new BaseFeeModel(TOKEN_GET_INFO, "Get metadata for a token"));
        registry.put(TOKEN_FREEZE_ACCOUNT, new BaseFeeModel(TOKEN_FREEZE_ACCOUNT, "Freeze a specific account with respect to a token"));
        registry.put(TOKEN_UNFREEZE_ACCOUNT, new BaseFeeModel(TOKEN_UNFREEZE_ACCOUNT, "Unfreeze a specific account with respect to a token"));
        registry.put(TOKEN_GRANT_KYC_TO_ACCOUNT, new BaseFeeModel(TOKEN_GRANT_KYC_TO_ACCOUNT, "Grant KYC status to an account for a specific token"));
        registry.put(TOKEN_REVOKE_KYC_FROM_ACCOUNT, new BaseFeeModel(TOKEN_REVOKE_KYC_FROM_ACCOUNT, "Revoke KYC status from an account for a specific token"));
        registry.put(TOKEN_DELETE, new BaseFeeModel(TOKEN_DELETE, "Delete a specific token"));
        registry.put(TOKEN_UPDATE, new BaseFeeModel(TOKEN_UPDATE, "Update a specific token"));
        registry.put(TOKEN_MINT, new BaseFeeModel(TOKEN_MINT, "Mint tokens"));
        registry.put(TOKEN_BURN, new BaseFeeModel(TOKEN_BURN, "Burn tokens"));
        registry.put(TOKEN_ACCOUNT_WIPE, new BaseFeeModel(TOKEN_ACCOUNT_WIPE, "Wipe all amounts for a specific token"));
        registry.put(TOKEN_ASSOCIATE_TO_ACCOUNT, new BaseFeeModel(TOKEN_ASSOCIATE_TO_ACCOUNT, "Associate account to a specific token"));
        registry.put(TOKEN_DISSOCIATE_FROM_ACCOUNT, new BaseFeeModel(TOKEN_DISSOCIATE_FROM_ACCOUNT, "Dissociate account from a specific token"));
        registry.put(TOKEN_PAUSE, new BaseFeeModel(TOKEN_PAUSE, "Pause a specific token"));
        registry.put(TOKEN_UNPAUSE, new BaseFeeModel(TOKEN_UNPAUSE, "Unpause a specific token"));
        registry.put(TOKEN_UPDATE_NFTS, new BaseFeeModel(TOKEN_UPDATE_NFTS, "Update metadata of an NFT token"));
        registry.put(TOKEN_REJECT, new BaseFeeModel(TOKEN_REJECT, "Reject a token"));
        registry.put(TOKEN_AIRDROP, new BaseFeeModel(TOKEN_AIRDROP, "Airdrop one or more tokens"));
        registry.put(TOKEN_CANCEL_AIRDROP, new BaseFeeModel(TOKEN_CANCEL_AIRDROP, "Cancel pending airdrops"));
        registry.put(TOKEN_CLAIM_AIRDROP, new BaseFeeModel(TOKEN_CLAIM_AIRDROP, "Claim pending airdrops"));

        registry.put(SCHEDULE_CREATE, new BaseFeeModel(SCHEDULE_CREATE, "Create a scheduled transaction"));
        registry.put(SCHEDULE_DELETE, new BaseFeeModel(SCHEDULE_DELETE, "Delete a scheduled transaction"));
        registry.put(SCHEDULE_SIGN, new BaseFeeModel(SCHEDULE_SIGN, "Sign a scheduled transaction"));
        registry.put(SCHEDULE_GET_INFO, new BaseFeeModel(SCHEDULE_GET_INFO, "Get metadata for a scheduled transaction"));


    }

    public static FeeModel lookupModel(HederaFunctionality service) {
        if (!registry.containsKey(service)) {
            throw new IllegalArgumentException("No registered model found for service " + service);
        }
        return registry.get(service);
    }
}

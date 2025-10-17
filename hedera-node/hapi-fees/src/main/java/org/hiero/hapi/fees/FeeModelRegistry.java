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
import org.hiero.hapi.fees.apis.common.StandardFeeModel;

/**
 * Registry of all fee models in the system. Internal admin only transactions
 * are currently omitted.
 */
public class FeeModelRegistry {
    private static final Map<String, FeeModel> registry = new LinkedHashMap<>();

    private static void register(FeeModel feeModel) {
        registry.put(feeModel.getApi(), feeModel);
    }

    static {
        register(new StandardFeeModel(CONSENSUS_CREATE_TOPIC, "Create a new topic"));
        register(new StandardFeeModel(CONSENSUS_CREATE_TOPIC.protoName() + "CustomFees", "Create a new topic"));
        register(new StandardFeeModel(CONSENSUS_UPDATE_TOPIC, "Update topic"));
        register(new StandardFeeModel(CONSENSUS_DELETE_TOPIC, "Delete topic"));
        register(new StandardFeeModel(CONSENSUS_GET_TOPIC_INFO, "Get metadata for a topic"));
        register(new StandardFeeModel(CONSENSUS_SUBMIT_MESSAGE, "Submit message to topic"));
        register(new StandardFeeModel(CONSENSUS_SUBMIT_MESSAGE.protoName() + "CustomFees", "Submit message to topic"));

        register(new StandardFeeModel(FILE_CREATE, "Create file"));
        register(new StandardFeeModel(FILE_APPEND, "Append to file"));
        register(new StandardFeeModel(FILE_UPDATE, "Update file"));
        register(new StandardFeeModel(FILE_DELETE, "Delete file"));
        register(new StandardFeeModel(FILE_GET_CONTENTS, "Get file contents"));
        register(new StandardFeeModel(FILE_GET_INFO, "Get file info"));

        register(new StandardFeeModel(CRYPTO_TRANSFER, "Transfer tokens among accounts"));
        register(new StandardFeeModel(CRYPTO_UPDATE, "Update an account"));
        register(new StandardFeeModel(CRYPTO_DELETE, "Delete an account"));
        register(new StandardFeeModel(CRYPTO_CREATE, "Create a new account"));
        register(new StandardFeeModel(CRYPTO_APPROVE_ALLOWANCE, "Approve an allowance for a spender"));
        register(new StandardFeeModel(CRYPTO_DELETE_ALLOWANCE, "Delete an allowance for a spender"));

        register(new StandardFeeModel(CONTRACT_CALL, "Execute a smart contract call"));
        register(new StandardFeeModel(CONTRACT_CREATE, "Create a smart contract"));
        register(new StandardFeeModel(CONTRACT_UPDATE, "Update a smart contract"));
        register(new StandardFeeModel(CONTRACT_GET_INFO, "Get information about a smart contract"));
        register(new StandardFeeModel(CONTRACT_GET_BYTECODE, "Get the compiled bytecode for a smart contract"));
        register(new StandardFeeModel(CONTRACT_DELETE, "Delete a smart contract"));

        register(new StandardFeeModel(TOKEN_CREATE, "Create a token"));
        register(new StandardFeeModel(TOKEN_GET_INFO, "Get metadata for a token"));
        register(new StandardFeeModel(TOKEN_FREEZE_ACCOUNT, "Freeze a specific account with respect to a token"));
        register(new StandardFeeModel(TOKEN_UNFREEZE_ACCOUNT, "Unfreeze a specific account with respect to a token"));
        register(new StandardFeeModel(
                TOKEN_GRANT_KYC_TO_ACCOUNT, "Grant KYC status to an account for a specific token"));
        register(new StandardFeeModel(
                TOKEN_REVOKE_KYC_FROM_ACCOUNT, "Revoke KYC status from an account for a specific token"));
        register(new StandardFeeModel(TOKEN_DELETE, "Delete a specific token"));
        register(new StandardFeeModel(TOKEN_UPDATE, "Update a specific token"));
        register(new StandardFeeModel(TOKEN_MINT, "Mint tokens"));
        register(new StandardFeeModel(TOKEN_BURN, "Burn tokens"));
        register(new StandardFeeModel(TOKEN_ACCOUNT_WIPE, "Wipe all amounts for a specific token"));
        register(new StandardFeeModel(TOKEN_ASSOCIATE_TO_ACCOUNT, "Associate account to a specific token"));
        register(new StandardFeeModel(TOKEN_DISSOCIATE_FROM_ACCOUNT, "Dissociate account from a specific token"));
        register(new StandardFeeModel(TOKEN_PAUSE, "Pause a specific token"));
        register(new StandardFeeModel(TOKEN_UNPAUSE, "Unpause a specific token"));
        register(new StandardFeeModel(TOKEN_UPDATE_NFTS, "Update metadata of an NFT token"));
        register(new StandardFeeModel(TOKEN_REJECT, "Reject a token"));
        register(new StandardFeeModel(TOKEN_AIRDROP, "Airdrop one or more tokens"));
        register(new StandardFeeModel(TOKEN_CANCEL_AIRDROP, "Cancel pending airdrops"));
        register(new StandardFeeModel(TOKEN_CLAIM_AIRDROP, "Claim pending airdrops"));

        register(new StandardFeeModel(SCHEDULE_CREATE, "Create a scheduled transaction"));
        register(new StandardFeeModel(SCHEDULE_DELETE, "Delete a scheduled transaction"));
        register(new StandardFeeModel(SCHEDULE_SIGN, "Sign a scheduled transaction"));
        register(new StandardFeeModel(SCHEDULE_GET_INFO, "Get metadata for a scheduled transaction"));
    }

    public static FeeModel lookupModel(HederaFunctionality service) {
        return lookupModel(service.protoName());
    }

    public static FeeModel lookupModel(String service) {
        if (!registry.containsKey(service)) {
            throw new IllegalArgumentException("No registered model found for service " + service);
        }
        return registry.get(service);
    }
}

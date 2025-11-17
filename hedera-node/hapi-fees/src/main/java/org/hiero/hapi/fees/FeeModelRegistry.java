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
    private static final Map<String , FeeModel> registry = new LinkedHashMap<>();

    private static void register(FeeModel feeModel) {
        registry.put(feeModel.getApi(), feeModel);
    }

    static {
        register(new StandardFeeModel(CONSENSUS_CREATE_TOPIC.protoName(), "Create a new topic"));
        register(new StandardFeeModel(CONSENSUS_UPDATE_TOPIC.protoName(), "Update topic"));
        register(new StandardFeeModel(CONSENSUS_DELETE_TOPIC.protoName(), "Delete topic"));
        register(new StandardFeeModel(CONSENSUS_GET_TOPIC_INFO.protoName(), "Get metadata for a topic"));
        register(new StandardFeeModel(CONSENSUS_SUBMIT_MESSAGE.protoName(), "Submit message to topic"));

        register(new StandardFeeModel(FILE_CREATE.protoName(), "Create file"));
        register(new StandardFeeModel(FILE_APPEND.protoName(), "Append to file"));
        register(new StandardFeeModel(FILE_UPDATE.protoName(), "Update file"));
        register(new StandardFeeModel(FILE_DELETE.protoName(), "Delete file"));
        register(new StandardFeeModel(FILE_GET_CONTENTS.protoName(), "Get file contents"));
        register(new StandardFeeModel(FILE_GET_INFO.protoName(), "Get file info"));

        register(new StandardFeeModel(CRYPTO_TRANSFER.protoName(), "Transfer tokens among accounts"));
        register(new StandardFeeModel(CRYPTO_UPDATE.protoName(), "Update an account"));
        register(new StandardFeeModel(CRYPTO_DELETE.protoName(), "Delete an account"));
        register(new StandardFeeModel(CRYPTO_CREATE.protoName(), "Create a new account"));
        register(new StandardFeeModel(CRYPTO_APPROVE_ALLOWANCE.protoName(), "Approve an allowance for a spender"));
        register(new StandardFeeModel(CRYPTO_DELETE_ALLOWANCE.protoName(), "Delete an allowance for a spender"));

        register(new StandardFeeModel(CONTRACT_CALL.protoName(), "Execute a smart contract call"));
        register(new StandardFeeModel(CONTRACT_CREATE.protoName(), "Create a smart contract"));
        register(new StandardFeeModel(CONTRACT_UPDATE.protoName(), "Update a smart contract"));
        register(new StandardFeeModel(CONTRACT_GET_INFO.protoName(), "Get information about a smart contract"));
        register(new StandardFeeModel(CONTRACT_GET_BYTECODE.protoName(), "Get the compiled bytecode for a smart contract"));
        register(new StandardFeeModel(CONTRACT_DELETE.protoName(), "Delete a smart contract"));

        register(new StandardFeeModel(TOKEN_CREATE.protoName(), "Create a token"));
        register(new StandardFeeModel(TOKEN_GET_INFO.protoName(), "Get metadata for a token"));
        register(new StandardFeeModel(TOKEN_FREEZE_ACCOUNT.protoName(), "Freeze a specific account with respect to a token"));
        register(new StandardFeeModel(TOKEN_UNFREEZE_ACCOUNT.protoName(), "Unfreeze a specific account with respect to a token"));
        register(new StandardFeeModel(
                TOKEN_GRANT_KYC_TO_ACCOUNT.protoName(), "Grant KYC status to an account for a specific token"));
        register(new StandardFeeModel(
                TOKEN_REVOKE_KYC_FROM_ACCOUNT.protoName(), "Revoke KYC status from an account for a specific token"));
        register(new StandardFeeModel(TOKEN_DELETE.protoName(), "Delete a specific token"));
        register(new StandardFeeModel(TOKEN_UPDATE.protoName(), "Update a specific token"));
        register(new StandardFeeModel(TOKEN_MINT.protoName(), "Mint tokens"));
        register(new StandardFeeModel(TOKEN_BURN.protoName(), "Burn tokens"));
        register(new StandardFeeModel(TOKEN_ACCOUNT_WIPE.protoName(), "Wipe all amounts for a specific token"));
        register(new StandardFeeModel(TOKEN_ASSOCIATE_TO_ACCOUNT.protoName(), "Associate account to a specific token"));
        register(new StandardFeeModel(TOKEN_DISSOCIATE_FROM_ACCOUNT.protoName(), "Dissociate account from a specific token"));
        register(new StandardFeeModel(TOKEN_PAUSE.protoName(), "Pause a specific token"));
        register(new StandardFeeModel(TOKEN_UNPAUSE.protoName(), "Unpause a specific token"));
        register(new StandardFeeModel(TOKEN_UPDATE_NFTS.protoName(), "Update metadata of an NFT token"));
        register(new StandardFeeModel(TOKEN_REJECT.protoName(), "Reject a token"));
        register(new StandardFeeModel(TOKEN_AIRDROP.protoName(), "Airdrop one or more tokens"));
        register(new StandardFeeModel(TOKEN_CANCEL_AIRDROP.protoName(), "Cancel pending airdrops"));
        register(new StandardFeeModel(TOKEN_CLAIM_AIRDROP.protoName(), "Claim pending airdrops"));

        register(new StandardFeeModel(SCHEDULE_CREATE.protoName(), "Create a scheduled transaction"));
        register(new StandardFeeModel(SCHEDULE_DELETE.protoName(), "Delete a scheduled transaction"));
        register(new StandardFeeModel(SCHEDULE_SIGN.protoName(), "Sign a scheduled transaction"));
        register(new StandardFeeModel(SCHEDULE_GET_INFO.protoName(), "Get metadata for a scheduled transaction"));
    }

    public static FeeModel lookupModel(HederaFunctionality service) {
        if (!registry.containsKey(service)) {
            throw new IllegalArgumentException("No registered model found for service " + service.protoName());
        }
        return registry.get(service);
    }
}

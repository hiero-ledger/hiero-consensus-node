/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.util.impl.handlers;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody.DataOneOfType;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A package-private utility class for Schedule Handlers.
 */
public final class HandlerUtility {
    private HandlerUtility() {}

    /**
     * Given a Transaction of one type, return the corresponding HederaFunctionality.
     * @param transactionType the transaction type
     * @return the hedera functionality
     */
    public static HederaFunctionality functionalityForType(@NonNull final DataOneOfType transactionType) {
        requireNonNull(transactionType);
        return switch (transactionType) {
            case CONSENSUS_CREATE_TOPIC -> HederaFunctionality.CONSENSUS_CREATE_TOPIC;
            case CONSENSUS_UPDATE_TOPIC -> HederaFunctionality.CONSENSUS_UPDATE_TOPIC;
            case CONSENSUS_DELETE_TOPIC -> HederaFunctionality.CONSENSUS_DELETE_TOPIC;
            case CONSENSUS_SUBMIT_MESSAGE -> HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
            case CRYPTO_ADD_LIVE_HASH -> HederaFunctionality.CRYPTO_ADD_LIVE_HASH;
            case CRYPTO_CREATE_ACCOUNT -> HederaFunctionality.CRYPTO_CREATE;
            case CRYPTO_UPDATE_ACCOUNT -> HederaFunctionality.CRYPTO_UPDATE;
            case CRYPTO_DELETE_LIVE_HASH -> HederaFunctionality.CRYPTO_DELETE_LIVE_HASH;
            case CRYPTO_TRANSFER -> HederaFunctionality.CRYPTO_TRANSFER;
            case CRYPTO_DELETE -> HederaFunctionality.CRYPTO_DELETE;
            case FILE_CREATE -> HederaFunctionality.FILE_CREATE;
            case FILE_APPEND -> HederaFunctionality.FILE_APPEND;
            case FILE_UPDATE -> HederaFunctionality.FILE_UPDATE;
            case FILE_DELETE -> HederaFunctionality.FILE_DELETE;
            case SYSTEM_DELETE -> HederaFunctionality.SYSTEM_DELETE;
            case SYSTEM_UNDELETE -> HederaFunctionality.SYSTEM_UNDELETE;
            case CONTRACT_CREATE_INSTANCE -> HederaFunctionality.CONTRACT_CREATE;
            case CONTRACT_UPDATE_INSTANCE -> HederaFunctionality.CONTRACT_UPDATE;
            case CONTRACT_CALL -> HederaFunctionality.CONTRACT_CALL;
            case CONTRACT_DELETE_INSTANCE -> HederaFunctionality.CONTRACT_DELETE;
            case FREEZE -> HederaFunctionality.FREEZE;
            case UNCHECKED_SUBMIT -> HederaFunctionality.UNCHECKED_SUBMIT;
            case TOKEN_CREATION -> HederaFunctionality.TOKEN_CREATE;
            case TOKEN_FREEZE -> HederaFunctionality.TOKEN_FREEZE_ACCOUNT;
            case TOKEN_UNFREEZE -> HederaFunctionality.TOKEN_UNFREEZE_ACCOUNT;
            case TOKEN_GRANT_KYC -> HederaFunctionality.TOKEN_GRANT_KYC_TO_ACCOUNT;
            case TOKEN_REVOKE_KYC -> HederaFunctionality.TOKEN_REVOKE_KYC_FROM_ACCOUNT;
            case TOKEN_DELETION -> HederaFunctionality.TOKEN_DELETE;
            case TOKEN_UPDATE -> HederaFunctionality.TOKEN_UPDATE;
            case TOKEN_MINT -> HederaFunctionality.TOKEN_MINT;
            case TOKEN_BURN -> HederaFunctionality.TOKEN_BURN;
            case TOKEN_WIPE -> HederaFunctionality.TOKEN_ACCOUNT_WIPE;
            case TOKEN_ASSOCIATE -> HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT;
            case TOKEN_DISSOCIATE -> HederaFunctionality.TOKEN_DISSOCIATE_FROM_ACCOUNT;
            case SCHEDULE_CREATE -> HederaFunctionality.SCHEDULE_CREATE;
            case SCHEDULE_DELETE -> HederaFunctionality.SCHEDULE_DELETE;
            case TOKEN_PAUSE -> HederaFunctionality.TOKEN_PAUSE;
            case TOKEN_UNPAUSE -> HederaFunctionality.TOKEN_UNPAUSE;
            case CRYPTO_APPROVE_ALLOWANCE -> HederaFunctionality.CRYPTO_APPROVE_ALLOWANCE;
            case CRYPTO_DELETE_ALLOWANCE -> HederaFunctionality.CRYPTO_DELETE_ALLOWANCE;
            case SCHEDULE_SIGN -> HederaFunctionality.SCHEDULE_SIGN;
            case TOKEN_FEE_SCHEDULE_UPDATE -> HederaFunctionality.TOKEN_FEE_SCHEDULE_UPDATE;
            case ETHEREUM_TRANSACTION -> HederaFunctionality.ETHEREUM_TRANSACTION;
            case NODE_STAKE_UPDATE -> HederaFunctionality.NODE_STAKE_UPDATE;
            case UTIL_PRNG -> HederaFunctionality.UTIL_PRNG;
            case TOKEN_UPDATE_NFTS -> HederaFunctionality.TOKEN_UPDATE_NFTS;
            case TOKEN_REJECT -> HederaFunctionality.TOKEN_REJECT;
            case NODE_CREATE -> HederaFunctionality.NODE_CREATE;
            case NODE_UPDATE -> HederaFunctionality.NODE_UPDATE;
            case NODE_DELETE -> HederaFunctionality.NODE_DELETE;
            case TOKEN_CANCEL_AIRDROP -> HederaFunctionality.TOKEN_CANCEL_AIRDROP;
            case TOKEN_CLAIM_AIRDROP -> HederaFunctionality.TOKEN_CLAIM_AIRDROP;
            case TOKEN_AIRDROP -> HederaFunctionality.TOKEN_AIRDROP;
            case UNSET, HISTORY_PROOF_SIGNATURE -> HederaFunctionality.NONE;
            case STATE_SIGNATURE_TRANSACTION -> HederaFunctionality.STATE_SIGNATURE_TRANSACTION;
            case HINTS_PREPROCESSING_VOTE -> HederaFunctionality.HINTS_PREPROCESSING_VOTE;
            case HINTS_KEY_PUBLICATION -> HederaFunctionality.HINTS_KEY_PUBLICATION;
            case HINTS_PARTIAL_SIGNATURE -> HederaFunctionality.HINTS_PARTIAL_SIGNATURE;
            case HISTORY_PROOF_KEY_PUBLICATION -> HederaFunctionality.HISTORY_PROOF_KEY_PUBLICATION;
            case HISTORY_PROOF_VOTE -> HederaFunctionality.HISTORY_PROOF_VOTE;
            case CRS_PUBLICATION -> HederaFunctionality.CRS_PUBLICATION;
            case ATOMIC_BATCH -> HederaFunctionality.ATOMIC_BATCH;
        };
    }
}

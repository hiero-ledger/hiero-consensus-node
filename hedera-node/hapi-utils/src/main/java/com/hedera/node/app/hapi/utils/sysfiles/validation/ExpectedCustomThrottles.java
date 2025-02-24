// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.sysfiles.validation;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_CREATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_DELETE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_GET_TOPIC_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_UPDATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL_LOCAL;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_GET_BYTECODE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_GET_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_GET_RECORDS;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_APPROVE_ALLOWANCE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_DELETE_ALLOWANCE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_GET_ACCOUNT_BALANCE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_GET_ACCOUNT_RECORDS;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_GET_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_APPEND;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_GET_CONTENTS;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_GET_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.GET_VERSION_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_GET_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_SIGN;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ACCOUNT_WIPE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_BURN;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_DISSOCIATE_FROM_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_FEE_SCHEDULE_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_FREEZE_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_GET_ACCOUNT_NFT_INFOS;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_GET_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_GET_NFT_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_GET_NFT_INFOS;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_GRANT_KYC_TO_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_MINT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_PAUSE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_REVOKE_KYC_FROM_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UNFREEZE_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UNPAUSE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TRANSACTION_GET_RECEIPT;
import static com.hedera.hapi.node.base.HederaFunctionality.TRANSACTION_GET_RECORD;
import static com.hedera.hapi.node.base.HederaFunctionality.UTIL_PRNG;

import com.hedera.hapi.node.base.HederaFunctionality;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class ExpectedCustomThrottles {
    public static final Set<HederaFunctionality> ACTIVE_OPS = Collections.unmodifiableSet(EnumSet.of(
            CRYPTO_CREATE,
            CRYPTO_TRANSFER,
            CRYPTO_UPDATE,
            CRYPTO_DELETE,
            CRYPTO_APPROVE_ALLOWANCE,
            CRYPTO_DELETE_ALLOWANCE,
            FILE_CREATE,
            FILE_UPDATE,
            FILE_DELETE,
            FILE_APPEND,
            CONTRACT_CREATE,
            CONTRACT_UPDATE,
            CONTRACT_CALL,
            ETHEREUM_TRANSACTION,
            CONTRACT_DELETE,
            CONSENSUS_CREATE_TOPIC,
            CONSENSUS_UPDATE_TOPIC,
            CONSENSUS_DELETE_TOPIC,
            CONSENSUS_SUBMIT_MESSAGE,
            TOKEN_CREATE,
            TOKEN_GET_NFT_INFO,
            TOKEN_GET_ACCOUNT_NFT_INFOS,
            TOKEN_GET_NFT_INFOS,
            TOKEN_FREEZE_ACCOUNT,
            TOKEN_UNFREEZE_ACCOUNT,
            TOKEN_GRANT_KYC_TO_ACCOUNT,
            TOKEN_REVOKE_KYC_FROM_ACCOUNT,
            TOKEN_DELETE,
            TOKEN_MINT,
            TOKEN_BURN,
            TOKEN_ACCOUNT_WIPE,
            TOKEN_UPDATE,
            TOKEN_ASSOCIATE_TO_ACCOUNT,
            TOKEN_DISSOCIATE_FROM_ACCOUNT,
            SCHEDULE_CREATE,
            SCHEDULE_SIGN,
            SCHEDULE_DELETE,
            CONSENSUS_GET_TOPIC_INFO,
            CONTRACT_CALL_LOCAL,
            CONTRACT_GET_INFO,
            CONTRACT_GET_BYTECODE,
            CONTRACT_GET_RECORDS,
            CRYPTO_GET_ACCOUNT_BALANCE,
            CRYPTO_GET_ACCOUNT_RECORDS,
            CRYPTO_GET_INFO,
            FILE_GET_CONTENTS,
            FILE_GET_INFO,
            TRANSACTION_GET_RECEIPT,
            TRANSACTION_GET_RECORD,
            GET_VERSION_INFO,
            TOKEN_GET_INFO,
            SCHEDULE_GET_INFO,
            TOKEN_FEE_SCHEDULE_UPDATE,
            TOKEN_PAUSE,
            TOKEN_UNPAUSE,
            UTIL_PRNG));

    private ExpectedCustomThrottles() {
        throw new UnsupportedOperationException("Utility Class");
    }
}

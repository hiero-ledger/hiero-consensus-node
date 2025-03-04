// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.sysfiles.validation;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_CREATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_DELETE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_GET_TOPIC_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_UPDATE_TOPIC;
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
import static com.hedera.node.app.hapi.utils.sysfiles.validation.ExpectedCustomThrottles.ACTIVE_OPS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExpectedCustomThrottlesTest {
    @Test
    // Suppress the warning that we use too many assets
    @SuppressWarnings("java:S5961")
    void releaseTwentyHasExpected() {
        assertEquals(56, ACTIVE_OPS.size());

        assertTrue(ACTIVE_OPS.contains(CRYPTO_CREATE), "Missing CryptoCreate!");
        assertTrue(ACTIVE_OPS.contains(CRYPTO_TRANSFER), "Missing CryptoTransfer!");
        assertTrue(ACTIVE_OPS.contains(CRYPTO_UPDATE), "Missing CryptoUpdate!");
        assertTrue(ACTIVE_OPS.contains(CRYPTO_DELETE), "Missing CryptoDelete!");
        assertTrue(ACTIVE_OPS.contains(CRYPTO_APPROVE_ALLOWANCE), "Missing CryptoApproveAllowance!");
        assertTrue(ACTIVE_OPS.contains(CRYPTO_DELETE_ALLOWANCE), "Missing CryptoDeleteAllowance!");
        assertTrue(ACTIVE_OPS.contains(FILE_CREATE), "Missing FileCreate!");
        assertTrue(ACTIVE_OPS.contains(FILE_UPDATE), "Missing FileUpdate!");
        assertTrue(ACTIVE_OPS.contains(FILE_DELETE), "Missing FileDelete!");
        assertTrue(ACTIVE_OPS.contains(FILE_APPEND), "Missing FileAppend!");
        assertTrue(ACTIVE_OPS.contains(CONTRACT_CREATE), "Missing ContractCreate!");
        assertTrue(ACTIVE_OPS.contains(CONTRACT_UPDATE), "Missing ContractUpdate!");
        assertTrue(ACTIVE_OPS.contains(CONTRACT_CREATE), "Missing ContractCreate!");
        assertTrue(ACTIVE_OPS.contains(CONTRACT_DELETE), "Missing ContractDelete!");
        assertTrue(ACTIVE_OPS.contains(CONSENSUS_CREATE_TOPIC), "Missing ConsensusCreateTopic!");
        assertTrue(ACTIVE_OPS.contains(CONSENSUS_UPDATE_TOPIC), "Missing ConsensusUpdateTopic!");
        assertTrue(ACTIVE_OPS.contains(CONSENSUS_DELETE_TOPIC), "Missing ConsensusDeleteTopic!");
        assertTrue(ACTIVE_OPS.contains(CONSENSUS_SUBMIT_MESSAGE), "Missing ConsensusSubmitMessage!");
        assertTrue(ACTIVE_OPS.contains(TOKEN_CREATE), "Missing TokenCreate!");
        assertTrue(ACTIVE_OPS.contains(TOKEN_FREEZE_ACCOUNT), "Missing TokenFreezeAccount!");
        assertTrue(ACTIVE_OPS.contains(TOKEN_GET_NFT_INFOS), "Missing TokenGetNftInfo!");
        assertTrue(ACTIVE_OPS.contains(TOKEN_GET_ACCOUNT_NFT_INFOS), "Missing TokenGetAccountNftInfos!");
        assertTrue(ACTIVE_OPS.contains(TOKEN_GET_NFT_INFOS), "Missing TokenGetNftInfos!");
        assertTrue(ACTIVE_OPS.contains(TOKEN_UNFREEZE_ACCOUNT), "Missing TokenUnfreezeAccount!");
        assertTrue(ACTIVE_OPS.contains(TOKEN_PAUSE), "Missing TokenPause!");
        assertTrue(ACTIVE_OPS.contains(TOKEN_UNPAUSE), "Missing TokenUnpause!");
        assertTrue(ACTIVE_OPS.contains(TOKEN_GRANT_KYC_TO_ACCOUNT), "Missing TokenGrantKycToAccount!");
        assertTrue(ACTIVE_OPS.contains(TOKEN_REVOKE_KYC_FROM_ACCOUNT), "Missing TokenRevokeKycFromAccount!");
        assertTrue(ACTIVE_OPS.contains(TOKEN_DELETE), "Missing TokenDelete!");
        assertTrue(ACTIVE_OPS.contains(TOKEN_MINT), "Missing TokenMint!");
        assertTrue(ACTIVE_OPS.contains(TOKEN_BURN), "Missing TokenBurn!");
        assertTrue(ACTIVE_OPS.contains(TOKEN_ACCOUNT_WIPE), "Missing TokenAccountWipe!");
        assertTrue(ACTIVE_OPS.contains(TOKEN_UPDATE), "Missing TokenUpdate!");
        assertTrue(ACTIVE_OPS.contains(TOKEN_ASSOCIATE_TO_ACCOUNT), "Missing TokenAssociateToAccount!");
        assertTrue(ACTIVE_OPS.contains(TOKEN_DISSOCIATE_FROM_ACCOUNT), "Missing TokenDissociateFromAccount!");
        assertTrue(ACTIVE_OPS.contains(TOKEN_FEE_SCHEDULE_UPDATE), "Missing TokenFeeScheduleUpdate!");
        assertTrue(ACTIVE_OPS.contains(SCHEDULE_CREATE), "Missing ScheduleCreate!");
        assertTrue(ACTIVE_OPS.contains(SCHEDULE_SIGN), "Missing ScheduleSign!");
        assertTrue(ACTIVE_OPS.contains(SCHEDULE_DELETE), "Missing ScheduleDelete!");
        assertTrue(ACTIVE_OPS.contains(CONSENSUS_GET_TOPIC_INFO), "Missing ConsensusGetTopicInfo!");
        assertTrue(ACTIVE_OPS.contains(CONTRACT_CALL_LOCAL), "Missing ContractCallLocal!");
        assertTrue(ACTIVE_OPS.contains(CONTRACT_GET_INFO), "Missing ContractGetInfo!");
        assertTrue(ACTIVE_OPS.contains(CONTRACT_GET_BYTECODE), "Missing ContractGetBytecode!");
        assertTrue(ACTIVE_OPS.contains(CONTRACT_GET_RECORDS), "Missing ContractGetRecords!");
        assertTrue(ACTIVE_OPS.contains(CRYPTO_GET_ACCOUNT_BALANCE), "Missing CryptoGetAccountBalance!");
        assertTrue(ACTIVE_OPS.contains(CRYPTO_GET_ACCOUNT_RECORDS), "Missing CryptoGetAccountRecords!");
        assertTrue(ACTIVE_OPS.contains(CRYPTO_GET_INFO), "Missing CryptoGetInfo!");
        assertTrue(ACTIVE_OPS.contains(FILE_GET_CONTENTS), "Missing FileGetContents!");
        assertTrue(ACTIVE_OPS.contains(FILE_GET_INFO), "Missing FileGetInfo!");
        assertTrue(ACTIVE_OPS.contains(TRANSACTION_GET_RECEIPT), "Missing TransactionGetReceipt!");
        assertTrue(ACTIVE_OPS.contains(TRANSACTION_GET_RECORD), "Missing TransactionGetRecord!");
        assertTrue(ACTIVE_OPS.contains(GET_VERSION_INFO), "Missing GetVersionInfo!");
        assertTrue(ACTIVE_OPS.contains(TOKEN_GET_INFO), "Missing TokenGetInfo!");
        assertTrue(ACTIVE_OPS.contains(SCHEDULE_GET_INFO), "Missing ScheduleGetInfo!");
        assertTrue(ACTIVE_OPS.contains(ETHEREUM_TRANSACTION), "Missing EthereumTransaction!");
        assertTrue(ACTIVE_OPS.contains(UTIL_PRNG), "Missing UtilPrng!");
    }
}

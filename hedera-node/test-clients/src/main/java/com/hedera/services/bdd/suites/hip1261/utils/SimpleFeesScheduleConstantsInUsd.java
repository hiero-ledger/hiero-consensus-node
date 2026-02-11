// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261.utils;

/**
 * Class with constants mirroring the Simple Fees JSON schedule used in tests.
 * All values here are in USD.
 */
public class SimpleFeesScheduleConstantsInUsd {

    /* ---------- Global node / network / unreadable ---------- */

    public static final double NODE_BASE_FEE_USD = 0.00001;
    public static final long NODE_INCLUDED_SIGNATURES = 1L;
    public static final long NODE_INCLUDED_BYTES = 1024L;

    public static final int NETWORK_MULTIPLIER = 9;
    public static final double NETWORK_BASE_FEE = NODE_BASE_FEE_USD * NETWORK_MULTIPLIER;
    public static final double NODE_AND_NETWORK_BASE_FEE = NODE_BASE_FEE_USD + NETWORK_BASE_FEE;
    public static final double UNREADABLE_FEE_USD = 0.00000001;

    /* ---------- Global extras price table ("extras") ---------- */

    public static final double SIGNATURE_FEE_USD = 0.00001;
    public static final double STATE_BYTES_FEE_USD = 0.0001;
    public static final double PROCESSING_BYTES_FEE_USD = 0.00001;
    public static final long STATE_BYTES_INCLUDED = 1000L;
    public static final long PROCESSING_BYTES_INCLUDED = 1024L;
    public static final double KEYS_FEE_USD = 0.01;
    public static final double NFT_SERIALS_FEE_USD = 0.00089;
    public static final double ACCOUNTS_FEE_USD = 0.0001;
    public static final double SIGNATURE_FEE_AFTER_MULTIPLIER = (NETWORK_MULTIPLIER + 1) * SIGNATURE_FEE_USD;

    public static final double TOKEN_TYPES_FEE = 0.0001;

    public static final double GAS_FEE_USD = 0.0000000852;
    public static final double ALLOWANCES_FEE_USD = 0.05;

    public static final double TOKEN_CREATE_WITH_CUSTOM_FEES_FEE_USD = 1.0;
    public static final double TOKEN_CREATE_BASE_FEE = 1.0;
    public static final double TOKEN_CREATE_WITH_CUSTOM_FEES_FEE = 2.0;
    public static final double TOKEN_MINT_FT_BASE_FEE = 0.001;
    public static final double TOKEN_MINT_NFT_FEE_USD = 0.02;
    public static final double TOKEN_UPDATE_BASE_FEE = 0.001;
    public static final double TOKEN_UPDATE_NFT_FEE = 0.001;
    public static final double TOKEN_TRANSFER_FULL_FEE = 0.001;
    public static final double TOKEN_ASSOCIATE_FEE = 0.05;

    public static final double AIRDROPS_FEE_USD = 0.05;
    public static final double TOKEN_CLAIM_FEE = 0.001;
    public static final double HOOK_UPDATES_FEE_USD = 1.0;
    public static final double HOOK_EXECUTION_FEE_USD = 0.005;

    public static final double TOKEN_TRANSFER_FEE = 0.001;
    public static final double TOKEN_TRANSFER_WITH_CUSTOM_FEE = 0.002;
    public static final double TOKEN_TRANSFER_BASE_FEE_USD = 0.0009;
    public static final double TOKEN_TRANSFER_BASE_CUSTOM_FEES_USD = 0.0019;

    public static final double CONS_SUBMIT_MESSAGE_BASE_FEE_USD = 0.0007;
    public static final long CONS_SUBMIT_MESSAGE_INCLUDED_BYTES = 1024L;
    public static final double CONS_CREATE_TOPIC_WITH_CUSTOM_FEE_USD = 1.99;
    public static final double CONS_SUBMIT_MESSAGE_WITH_CUSTOM_FEE_USD = 0.0499;
    public static final double SCHEDULE_CREATE_CONTRACT_CALL_BASE_FEE_USD = 0.0499;

    /* ---------- Crypto service ---------- */

    public static final double CRYPTO_CREATE_BASE_FEE_USD = 0.0499;
    public static final long CRYPTO_CREATE_INCLUDED_KEYS = 1L;
    public static final long CRYPTO_CREATE_INCLUDED_HOOKS = 0L;

    public static final double CRYPTO_UPDATE_FEE = 0.00022;
    public static final double CRYPTO_UPDATE_BASE_FEE_USD = 0.00012;
    public static final long CRYPTO_UPDATE_INCLUDED_KEYS = 1L;
    public static final long CRYPTO_UPDATE_INCLUDED_HOOKS = 0L;

    public static final double CRYPTO_DELETE_BASE_FEE_USD = 0.0049;

    public static final double CRYPTO_TRANSFER_BASE_FEE_USD = 0;
    public static final long CRYPTO_TRANSFER_INCLUDED_HOOK_EXECUTION = 0L;
    public static final long CRYPTO_TRANSFER_INCLUDED_GAS = 0L;
    public static final long CRYPTO_TRANSFER_INCLUDED_ACCOUNTS = 2L;
    public static final long INCLUDED_TOKEN_TYPES = 1L;

    public static final double CRYPTO_APPROVE_ALLOWANCE_FEE = 0.05;
    public static final double CRYPTO_DELETE_ALLOWANCE_FEE = 0.05;

    /* ---------- Consensus service ---------- */

    public static final double CONS_CREATE_TOPIC_BASE_FEE_USD = 0.0099;
    public static final long CONS_CREATE_TOPIC_INCLUDED_KEYS = 0L;
    public static final long CONS_CREATE_TOPIC_WITH_CUSTOM_FEES = 0L;

    public static final double CONS_UPDATE_TOPIC_BASE_FEE_USD = 0.00012;
    public static final long CONS_UPDATE_TOPIC_INCLUDED_KEYS = 1L;

    public static final double SUBMIT_MESSAGE_BASE_FEE_USD = 0.0008;
    public static final double SUBMIT_MESSAGE_WITH_CUSTOM_FEE_BASE_USD = 0.05;

    public static final double CONS_DELETE_TOPIC_BASE_FEE_USD = 0.0049;

    public static final double CONS_GET_TOPIC_INFO_BASE_FEE_USD = 0.0;

    /* ---------- File service ---------- */
    public static final double FILE_CREATE_FEE_USD = 0.05;
    public static final double FILE_UPDATE_FEE_USD = 0.05;
    public static final double FILE_DELETE_FEE_USD = 0.007;
    public static final double FILE_APPEND_FEE_USD = 0.05;
    public static final double FILE_GET_CONTENTS_BASE_FEE_USD = 0.000000001;

    /* ---------- Token service ---------- */
    public static final long TOKEN_CREATE_WITH_CUSTOM_FEE_USD = 1L;

    public static final double TOKEN_CREATE_BASE_FEE_USD = 0.9999;
    public static final long TOKEN_CREATE_INCLUDED_KEYS = 1L;

    public static final double TOKEN_UPDATE_BASE_FEE_USD = 0.0009;
    public static final long TOKEN_UPDATE_INCLUDED_KEYS = 1L;
    public static final long TOKEN_UPDATE_INCLUDED_NFTS = 1L;

    public static final double TOKEN_DELETE_BASE_FEE_USD = 0.0009;

    public static final double TOKEN_MINT_BASE_FEE_USD = 0.0009;
    public static final long TOKEN_MINT_INCLUDED_NFT = 0L;

    public static final double TOKEN_BURN_BASE_FEE_USD = 0.0009;

    public static final double TOKEN_ASSOCIATE_BASE_FEE_USD = 0.0499;
    public static final long TOKEN_ASSOCIATE_INCLUDED_TOKENS = 1L;
    public static final double TOKEN_ASSOCIATE_EXTRA_FEE_USD = 0.05;

    public static final double TOKEN_DISSOCIATE_BASE_FEE_USD = 0.0499;

    public static final double TOKEN_GRANT_KYC_BASE_FEE_USD = 0.0009;

    public static final double TOKEN_REVOKE_KYC_BASE_FEE_USD = 0.0009;

    public static final double TOKEN_FREEZE_BASE_FEE_USD = 0.0009;
    public static final double TOKEN_UNFREEZE_BASE_FEE_USD = 0.0009;
    public static final double TOKEN_FREEZE_FEE = 0.001;
    public static final double TOKEN_UNFREEZE_FEE = 0.001;

    public static final double TOKEN_PAUSE_BASE_FEE_USD = 0.0009;

    public static final double TOKEN_UNPAUSE_BASE_FEE_USD = 0.0009;

    public static final double TOKEN_WIPE_BASE_FEE_USD = 0.0009;
    public static final double TOKEN_WIPE_FEE = 0.001;

    /* ---------- Schedule service ---------- */

    public static final double SCHEDULE_CREATE_BASE_FEE_USD = 0.0099;
    public static final long SCHEDULE_CREATE_INCLUDED_KEYS = 1L;
    public static final double SCHEDULE_SIGN_FEE = 0.001;
    public static final double SCHEDULE_DELETE_BASE_FEE_USD = 0.0009;

    /* ---------- Atomic Batch service ------------ */
    public static final double BATCH_BASE_FEE = 0.001;
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261.utils;

/**
 * Class with constants mirroring the Simple Fees JSON schedule used in tests.
 * All values here are in tinycents.
 */
public class SimpleFeesScheduleConstants {

    /* ---------- Global node / network / unreadable ---------- */

    // "node": { "baseFee": 100000, "extras": [ { "name": "SIGNATURES", "includedCount": 1 } ] }
    public static final long NODE_BASE_FEE_TINYCENTS = 100_000L;
    public static final long NODE_INCLUDED_SIGNATURES = 1L;

    // "network": { "multiplier": 9 }
    public static final int NETWORK_MULTIPLIER = 9;

    // "unreadable": { "feeValue": 100 }
    public static final long UNREADABLE_FEE_TINYCENTS = 100L;

    /* ---------- Global extras price table ("extras" array) ---------- */

    // { "name":  "SIGNATURES", "fee": 1000000 }
    public static final long SIGNATURE_FEE_TINYCENTS = 1_000_000L;

    // { "name":  "BYTES", "fee": 110 }
    public static final long BYTES_FEE_TINYCENTS = 110L;

    // { "name":  "KEYS", "fee": 100000000 }
    public static final long KEYS_FEE_TINYCENTS = 100_000_000L;

    // { "name":  "TOKEN_TYPES", "fee": 0 }
    public static final long TOKEN_TYPES_FEE_TINYCENTS = 0L;

    // { "name":  "NFT_SERIALS", "fee": 8900000 }
    public static final long NFT_SERIALS_FEE_TINYCENTS = 8_900_000L;

    // { "name":  "ACCOUNTS", "fee": 0 }
    public static final long ACCOUNTS_FEE_TINYCENTS = 0L;

    // { "name":  "STANDARD_FUNGIBLE_TOKENS", "fee": 9000000 }
    public static final long STANDARD_FT_FEE_TINYCENTS = 9_000_000L;

    // { "name":  "STANDARD_NON_FUNGIBLE_TOKENS", "fee": 100000 }
    public static final long STANDARD_NFT_FEE_TINYCENTS = 100_000L;

    // { "name":  "CUSTOM_FEE_FUNGIBLE_TOKENS", "fee": 19000000 }
    public static final long CUSTOM_FEE_FT_FEE_TINYCENTS = 19_000_000L;

    // { "name":  "CUSTOM_FEE_NON_FUNGIBLE_TOKENS", "fee": 10100000 }
    public static final long CUSTOM_FEE_NFT_FEE_TINYCENTS = 10_100_000L;

    // { "name":  "CREATED_AUTO_ASSOCIATIONS", "fee": 0 }
    public static final long CREATED_AUTO_ASSOCIATIONS_FEE_TINYCENTS = 0L;

    // { "name":  "CREATED_ACCOUNTS", "fee": 0 }
    public static final long CREATED_ACCOUNTS_FEE_TINYCENTS = 0L;

    // { "name":  "CUSTOM_FEE", "fee": 0 }
    public static final long CUSTOM_FEE_FEE_TINYCENTS = 0L;

    // { "name":  "GAS", "fee": 1 }
    public static final long GAS_FEE_TINYCENTS = 1L;

    // { "name":  "ALLOWANCES", "fee": 2000 }
    public static final long ALLOWANCES_FEE_TINYCENTS = 2_000L;

    // { "name":  "AIRDROPS", "fee": 8800 }
    public static final long AIRDROPS_FEE_TINYCENTS = 8_800L;

    // { "name":  "HOOKS", "fee": 10000000000 }
    public static final long HOOKS_FEE_TINYCENTS = 10_000_000_000L;

    /* ---------- Crypto service ("services" -> "Crypto") ---------- */

    // --- CryptoCreate ---
    // "baseFee": 499000000
    public static final long CRYPTO_CREATE_BASE_FEE_TINYCENTS = 499_000_000L;
    // "extras": [ { "name": "KEYS", "includedCount": 1 }, { "name": "HOOKS", "includedCount": 0 } ]
    public static final long CRYPTO_CREATE_INCLUDED_KEYS = 1L;
    public static final long CRYPTO_CREATE_INCLUDED_HOOKS = 0L;

    // --- CryptoUpdate ---
    // "baseFee": 1200000
    public static final long CRYPTO_UPDATE_BASE_FEE_TINYCENTS = 1_200_000L;
    // "extras": [ { "name": "KEYS", "includedCount": 1 }, { "name": "HOOKS", "includedCount": 0 } ]
    public static final long CRYPTO_UPDATE_INCLUDED_KEYS = 1L;
    public static final long CRYPTO_UPDATE_INCLUDED_HOOKS = 0L;

    // --- CryptoDelete ---
    // "baseFee": 49000000
    public static final long CRYPTO_DELETE_BASE_FEE_TINYCENTS = 49_000_000L;
    // no extras for CryptoDelete

    // --- CryptoTransfer ---
    // "baseFee": 18
    public static final long CRYPTO_TRANSFER_BASE_FEE_TINYCENTS = 18L;
    // "extras": KEYS, ACCOUNTS, STANDARD_FUNGIBLE_TOKENS all with includedCount = 1
    public static final long CRYPTO_TRANSFER_INCLUDED_KEYS = 1L;
    public static final long CRYPTO_TRANSFER_INCLUDED_ACCOUNTS = 1L;
    public static final long CRYPTO_TRANSFER_INCLUDED_STD_FT = 1L;

    /* ---------- Consensus service ("services" -> "Consensus") ---------- */

    // --- ConsensusCreateTopic ---
    // "baseFee": 99000000
    public static final long CONS_CREATE_TOPIC_BASE_FEE_TINYCENTS = 99_000_000L;
    // "extras": [ { "name": "KEYS", "includedCount": 0 } ]
    public static final long CONS_CREATE_TOPIC_INCLUDED_KEYS = 0L;

    // --- ConsensusUpdateTopic ---
    // "baseFee": 1200000
    public static final long CONS_UPDATE_TOPIC_BASE_FEE_TINYCENTS = 1_200_000L;
    // "extras": [ { "name": "KEYS", "includedCount": 1 } ]
    public static final long CONS_UPDATE_TOPIC_INCLUDED_KEYS = 1L;

    // --- ConsensusSubmitMessage ---
    // "baseFee": 0
    public static final long CONS_SUBMIT_MESSAGE_BASE_FEE_TINYCENTS = 0L;
    // "extras": [ { "name": "BYTES", "includedCount": 100 } ]
    public static final long CONS_SUBMIT_MESSAGE_INCLUDED_BYTES = 100L;

    // --- ConsensusDeleteTopic ---
    // "baseFee": 49000000
    public static final long CONS_DELETE_TOPIC_BASE_FEE_TINYCENTS = 49_000_000L;

    // --- ConsensusGetTopicInfo ---
    // "baseFee": 5000000
    public static final long CONS_GET_TOPIC_INFO_BASE_FEE_TINYCENTS = 5_000_000L;

    /* ---------- File service ("services" -> "File") ---------- */

    // --- FileCreate ---
    // "baseFee": 50
    public static final long FILE_CREATE_BASE_FEE_TINYCENTS = 50L;
    // "extras": KEYS (1), BYTES (1000)
    public static final long FILE_CREATE_INCLUDED_KEYS = 1L;
    public static final long FILE_CREATE_INCLUDED_BYTES = 1_000L;

    // --- FileUpdate ---
    // "baseFee": 50
    public static final long FILE_UPDATE_BASE_FEE_TINYCENTS = 50L;
    // "extras": KEYS (1), BYTES (1000)
    public static final long FILE_UPDATE_INCLUDED_KEYS = 1L;
    public static final long FILE_UPDATE_INCLUDED_BYTES = 1_000L;

    // --- FileAppend ---
    // "baseFee": 50
    public static final long FILE_APPEND_BASE_FEE_TINYCENTS = 50L;
    // "extras": KEYS (1), BYTES (1000)
    public static final long FILE_APPEND_INCLUDED_KEYS = 1L;
    public static final long FILE_APPEND_INCLUDED_BYTES = 1_000L;

    // --- FileDelete ---
    // "baseFee": 7
    public static final long FILE_DELETE_BASE_FEE_TINYCENTS = 7L;
    // "extras": KEYS (1)
    public static final long FILE_DELETE_INCLUDED_KEYS = 1L;

    // --- FileGetContents ---
    // "baseFee": 10
    public static final long FILE_GET_CONTENTS_BASE_FEE_TINYCENTS = 10L;
    // "extras": KEYS (1), BYTES (1000)
    public static final long FILE_GET_CONTENTS_INCLUDED_KEYS = 1L;
    public static final long FILE_GET_CONTENTS_INCLUDED_BYTES = 1_000L;

    /* ---------- Token service ("services" -> "Token") ---------- */

    // --- TokenCreate ---
    // "baseFee": 25
    public static final long TOKEN_CREATE_BASE_FEE_TINYCENTS = 25L;
    // no extras for TokenCreate

    // --- TokenMint ---
    // "baseFee": 33
    public static final long TOKEN_MINT_BASE_FEE_TINYCENTS = 33L;
    // "extras": STANDARD_FUNGIBLE_TOKENS (1), STANDARD_NON_FUNGIBLE_TOKENS (1)
    public static final long TOKEN_MINT_INCLUDED_STD_FT = 1L;
    public static final long TOKEN_MINT_INCLUDED_STD_NFT = 1L;

    /* ---------- Schedule service ("services" -> "Schedule") ---------- */

    // --- ScheduleCreate ---
    // "baseFee": 99000000
    public static final long SCHEDULE_CREATE_BASE_FEE_TINYCENTS = 99_000_000L;
    // "extras": KEYS (1)
    public static final long SCHEDULE_CREATE_INCLUDED_KEYS = 1L;

    // --- ScheduleSign ---
    // "baseFee": 9000000
    public static final long SCHEDULE_SIGN_BASE_FEE_TINYCENTS = 9_000_000L;
    // no extras for ScheduleSign

    // --- ScheduleDelete ---
    // "baseFee": 9000000
    public static final long SCHEDULE_DELETE_BASE_FEE_TINYCENTS = 9_000_000L;
    // no extras for ScheduleDelete

}

package com.hedera.node.app.hapi.fees.apis.common;

public final class FeeConstants {
    private FeeConstants() {}  // prevent instantiation

    public static final int MIN_GAS = 21_000;
    public static final int MAX_GAS = 15_000_000;

    public static final int FREE_KEYS_DEFAULT = 1; // First 1 key is included in the base fee: adminKey
    public static final int FREE_KEYS_TOKEN = 7; // First 7 keys are included in the base fee: adminKey, kycKey, freezeKey, wipeKey, supplyKey, feeScheduleKey, pauseKey

    public static final int MIN_KEYS = 1;
    public static final int MAX_KEYS = 100;

    public static final int TOKEN_FREE_TOKENS = 1;


    public static final int HCS_FREE_BYTES = 256;
    public static final int HCS_MIN_BYTES = 1;
    public static final int HCS_MAX_BYTES = 1024;

    public static final int FILE_FREE_BYTES = 1000;
    public static final int FILE_MIN_BYTES = 1;
    public static final int FILE_MAX_BYTES = 128*1024;

    public static final int FREE_ALLOWANCES = 1;
    public static final int MIN_ALLOWANCES = 1;
    public static final int MAX_ALLOWANCES = 10;

    public static final int MIN_SIGNATURES = 1;
    public static final int MAX_SIGNATURES = 100;
}
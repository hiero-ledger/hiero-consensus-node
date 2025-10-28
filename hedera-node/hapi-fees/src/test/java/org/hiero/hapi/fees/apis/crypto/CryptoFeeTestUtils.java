// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees.apis.crypto;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_APPROVE_ALLOWANCE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_DELETE_ALLOWANCE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_GET_ACCOUNT_RECORDS;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_GET_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_UPDATE;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;

import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;

/**
 * Utility class providing shared test fixtures for crypto fee model tests.
 */
public class CryptoFeeTestUtils {

    /**
     * Creates a test fee schedule with all crypto operations configured.
     *
     * <p>Fee Schedule Structure:
     * <ul>
     *   <li>Node base fee: 0, includes 10 signatures
     *   <li>Network multiplier: 2x node fees
     *   <li>Service fees: per-operation base + extras
     * </ul>
     *
     * <p>Extras pricing (in tinycents):
     * <ul>
     *   <li>SIGNATURES: 60,000,000
     *   <li>KEYS: 2,200,000
     *   <li>ACCOUNTS: 3
     *   <li>STANDARD_FUNGIBLE_TOKENS: 3
     *   <li>STANDARD_NON_FUNGIBLE_TOKENS: 3
     *   <li>NFT_SERIALS: 1
     *   <li>ALLOWANCES: 2,000
     *   <li>BYTES: 300
     * </ul>
     *
     * @return configured fee schedule for crypto operation tests
     */
    public static FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(
                        makeExtraDef(Extra.SIGNATURES, 60000000),
                        makeExtraDef(Extra.KEYS, 2200000),
                        makeExtraDef(Extra.ACCOUNTS, 3),
                        makeExtraDef(Extra.STANDARD_FUNGIBLE_TOKENS, 3),
                        makeExtraDef(Extra.STANDARD_NON_FUNGIBLE_TOKENS, 3),
                        makeExtraDef(Extra.NFT_SERIALS, 1),
                        makeExtraDef(Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 3),
                        makeExtraDef(Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 3),
                        makeExtraDef(Extra.CREATED_AUTO_ASSOCIATIONS, 3),
                        makeExtraDef(Extra.CREATED_ACCOUNTS, 3),
                        makeExtraDef(Extra.ALLOWANCES, 2000),
                        makeExtraDef(Extra.BYTES, 300))
                .node(NodeFee.DEFAULT
                        .copyBuilder()
                        .baseFee(0)
                        .extras(makeExtraIncluded(Extra.SIGNATURES, 10))
                        .build())
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(2).build())
                .services(makeService(
                        "Crypto",
                        makeServiceFee(
                                CRYPTO_CREATE,
                                22,
                                makeExtraIncluded(Extra.SIGNATURES, 1),
                                makeExtraIncluded(Extra.KEYS, 1)),
                        makeServiceFee(
                                CRYPTO_UPDATE,
                                22,
                                makeExtraIncluded(Extra.SIGNATURES, 1),
                                makeExtraIncluded(Extra.KEYS, 1)),
                        makeServiceFee(
                                CRYPTO_TRANSFER,
                                18,
                                makeExtraIncluded(Extra.SIGNATURES, 1),
                                makeExtraIncluded(Extra.ACCOUNTS, 1),
                                makeExtraIncluded(Extra.STANDARD_FUNGIBLE_TOKENS, 1),
                                makeExtraIncluded(Extra.STANDARD_NON_FUNGIBLE_TOKENS, 0),
                                makeExtraIncluded(Extra.NFT_SERIALS, 0),
                                makeExtraIncluded(Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 0),
                                makeExtraIncluded(Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 0),
                                makeExtraIncluded(Extra.CREATED_AUTO_ASSOCIATIONS, 0),
                                makeExtraIncluded(Extra.CREATED_ACCOUNTS, 0)),
                        makeServiceFee(CRYPTO_DELETE, 15, makeExtraIncluded(Extra.SIGNATURES, 1)),
                        makeServiceFee(
                                CRYPTO_APPROVE_ALLOWANCE,
                                20,
                                makeExtraIncluded(Extra.SIGNATURES, 1),
                                makeExtraIncluded(Extra.ALLOWANCES, 1)),
                        makeServiceFee(
                                CRYPTO_DELETE_ALLOWANCE,
                                15,
                                makeExtraIncluded(Extra.SIGNATURES, 1),
                                makeExtraIncluded(Extra.ALLOWANCES, 1)),
                        makeServiceFee(CRYPTO_GET_INFO, 10, makeExtraIncluded(Extra.SIGNATURES, 1)),
                        makeServiceFee(CRYPTO_GET_ACCOUNT_RECORDS, 15, makeExtraIncluded(Extra.SIGNATURES, 1))))
                .build();
    }

    private CryptoFeeTestUtils() {}
}

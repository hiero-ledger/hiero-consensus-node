// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesChargePolicy.INVALID_TXN_AT_PRE_HANDLE_ZERO_PAYER;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesChargePolicy.SUCCESS_TXN_FULL_CHARGE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesChargePolicy.UNHANDLED_TXN_NODE_AND_NETWORK_CHARGE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesReferenceTestCalculator.compute;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesReferenceTestCalculator.computeWithPolicy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesJsonLoader;
import com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesParams;
import com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesReferenceTestCalculator;
import java.util.Map;
import org.hiero.hapi.support.fees.Extra;
import org.junit.jupiter.api.Test;

public class SimpleFeesReferenceTestCalculatorTest {

    /**
     *  A simple JSON fee schedule for testing purposes.
     */
    private static final String TEST_JSON_SCHEDULE =
            """
            {
              "node": {
                "baseFee": 1000,
                "extras": [ { "name": "SIGNATURES", "includedCount": 1 } ]
              },
              "network": { "multiplier": 2 },
              "extras": [
                { "name": "SIGNATURES",  "fee": 100000 },
                { "name": "BYTES",       "fee": 3 },
                { "name": "KEYS",        "fee": 500 },
                { "name": "ACCOUNTS",    "fee": 200 }
              ],
              "services": [
                {
                  "name": "Crypto",
                  "schedule": [
                    {
                      "name": "CryptoCreate",
                      "baseFee": 499000000,
                      "extras": [
                        { "name": "KEYS", "includedCount": 1 }
                      ]
                    }
                  ]
                }
              ]
            }
            """;

    private SimpleFeesReferenceTestCalculator.Prepared preparedSchedule() throws Exception {
        final var jsonSchedule = SimpleFeesJsonLoader.fromString(TEST_JSON_SCHEDULE);
        return SimpleFeesReferenceTestCalculator.prepare(jsonSchedule);
    }

    @Test
    void prepareBuildsExpectedNodeAndServiceMaps() throws Exception {
        final var prepared = preparedSchedule();

        // Validate node fees
        assertEquals(1000, prepared.nodeBase());
        assertEquals(1, prepared.nodeIncludedByExtra().get(Extra.SIGNATURES));

        // Validate network multiplier
        assertEquals(2, prepared.networkMultiplier());

        // Validate global extras prices
        assertEquals(100000, prepared.priceByExtra().get(Extra.SIGNATURES));
        assertEquals(3, prepared.priceByExtra().get(Extra.BYTES));
        assertEquals(500, prepared.priceByExtra().get(Extra.KEYS));
        assertEquals(200, prepared.priceByExtra().get(Extra.ACCOUNTS));

        // Validate service fees
        final var serviceBaseFee = prepared.serviceBaseByApi().get(CRYPTO_CREATE);
        assertEquals(499000000, serviceBaseFee);

        // Validate service extras
        final var serviceIncludedExtras =
                prepared.serviceIncludedByApiAndExtra().get(CRYPTO_CREATE);
        assertEquals(1, serviceIncludedExtras.get(Extra.KEYS));
    }

    @Test
    void computeReturnsExpectedComponentsForCryptoCreateWithoutExtras() throws Exception {
        final var prepared = preparedSchedule();

        final Map<Extra, Long> extrasCount = SimpleFeesParams.create().get();

        final var result = compute(prepared, CRYPTO_CREATE, extrasCount);

        // node fee: 1000 + (0 * 100000) = 1000
        assertEquals(1000, result.node());
        // node extras: 0
        assertEquals(0, result.nodeExtras());
        // network fee: 2 * 1000 = 2000
        assertEquals(2000, result.network());
        // service fee: 499000000 + (0 * 500) = 499000000
        assertEquals(499000000, result.service());
        // service extras: 0
        assertEquals(0, result.serviceExtras());
        // total fee: 1000 + 0 + 2000 + 499000000 + 0 = 499003000
        assertEquals(499003000, result.total());
    }

    @Test
    void computeReturnsExpectedComponentsForCryptoCreateWithExtras() throws Exception {
        final var prepared = preparedSchedule();

        final Map<Extra, Long> extrasCount = SimpleFeesParams.create()
                .signatures(3L)
                .keys(2L)
                .bytes(150L)
                .accounts(1L)
                .get();

        final var result = compute(prepared, CRYPTO_CREATE, extrasCount);

        // node fee: 1000 + (2 * 100000) + (2 * 500) + (3 * 150) + (1 * 200) = 202650
        assertEquals(202650, result.node());
        // node extras: (2 * 100000) + (2 * 500) + (3 * 150) + (1 * 200) = 201650
        assertEquals(201650, result.nodeExtras());
        // network fee: 2 * 202650 = 402000
        assertEquals(405300, result.network());
        // service fee: 499000000 + (1 * 500) + (3 * 150) + (1 * 200) + (3 * 100000)  = 499301150
        assertEquals(499301150, result.service());
        // service extras: (1 * 500) + (3 * 150) + (1 * 200)  + (3 * 100000) = 301150
        assertEquals(301150, result.serviceExtras());
        // total fee: 202650 + 405300 + 499301150 = 499909100
        assertEquals(499909100, result.total());
    }

    @Test
    void computeWithZeroPayerChargedPolicyReturnsExpectedComponents() throws Exception {
        final var prepared = preparedSchedule();

        final Map<Extra, Long> extrasCount =
                SimpleFeesParams.create().signatures(1L).get();

        final var result =
                computeWithPolicy(prepared, CRYPTO_CREATE, extrasCount, INVALID_TXN_AT_PRE_HANDLE_ZERO_PAYER);

        // node fee: 1000
        assertEquals(1000, result.node());
        // node extras: 0
        assertEquals(0, result.nodeExtras());
        // network fee: 2 * 1000 = 2000
        assertEquals(2000, result.network());
        // service fee: 499000000 + (1 * 100000) = 499100000
        assertEquals(499100000, result.service());
        // assert payer is not charged
        assertEquals(0, result.payerCharged());
    }

    @Test
    void computeWithNodeAndNetworkOnlyChargedPolicyReturnsExpectedComponents() throws Exception {
        final var prepared = preparedSchedule();

        final Map<Extra, Long> extrasCount = SimpleFeesParams.create()
                .signatures(2L)
                .keys(1L)
                .bytes(50L)
                .accounts(1L)
                .get();

        final var result =
                computeWithPolicy(prepared, CRYPTO_CREATE, extrasCount, UNHANDLED_TXN_NODE_AND_NETWORK_CHARGE);

        // node fee: 1000 + (1 * 100000) + (1 * 500) + (3 * 50) + (1 * 200) = 101850
        assertEquals(101850, result.node());
        // node extras: (1 * 100000) + (1 * 500) + (3 * 50) + (1 * 200) = 101850
        assertEquals(100850, result.nodeExtras());
        // network fee: 2 * 101850 = 203700
        assertEquals(203700, result.network());
        // service fee: 499000000 + (3 * 50) + (1 * 200) + (2 * 100000) = 499200350
        assertEquals(499200350, result.service());
        // service extras: (3 * 50) + (1 * 200) + (2 * 100000) = 200350
        assertEquals(200350, result.serviceExtras());
        // assert payer is charged only node + network fees: 101850 + 203700 = 305550
        assertEquals(305550, result.payerCharged());
    }

    @Test
    void computeWithFullChargesUsesTotalFees() throws Exception {
        final var prepared = preparedSchedule();

        final Map<Extra, Long> extrasCount = SimpleFeesParams.create()
                .signatures(3L)
                .keys(1L)
                .bytes(50L)
                .accounts(1L)
                .get();

        final var result = compute(prepared, CRYPTO_CREATE, extrasCount);
        final var charges = computeWithPolicy(prepared, CRYPTO_CREATE, extrasCount, SUCCESS_TXN_FULL_CHARGE);

        // assert components charged
        assertEquals(result.node(), charges.node());
        assertEquals(result.network(), charges.network());
        assertEquals(result.service(), charges.service());
        // assert total fees charged
        assertEquals(result.total(), charges.payerCharged());
        // assert extras charged
        assertEquals(result.nodeExtras(), charges.nodeExtras());
        assertEquals(result.serviceExtras(), charges.serviceExtras());
        // assert total fees
        assertEquals(result.total(), charges.payerCharged());
    }

    @Test
    void computeIgnoresExtrasWithoutDefinedPrices() throws Exception {
        final var jsonSchedule = SimpleFeesJsonLoader.fromString(
                """
        {
          "node": { "baseFee": 1000 },
          "network": { "multiplier": 1 },
          "services": [
            {
              "name": "Crypto",
              "schedule": [
                {
                  "name": "CryptoCreate",
                  "baseFee": 499000000,
                  "extras": []
                }
              ]
            }
          ]
        }
        """);

        final var prepared = SimpleFeesReferenceTestCalculator.prepare(jsonSchedule);

        final Map<Extra, Long> extrasCount = SimpleFeesParams.create()
                .signatures(5L) // no price defined for SIGNATURES
                .get();

        final var result = compute(prepared, CRYPTO_CREATE, extrasCount);

        // node fee: 1000
        assertEquals(1000, result.node());
        // node extras: 0
        assertEquals(0, result.nodeExtras());
        // service extras: 0
        assertEquals(0, result.serviceExtras());
        // network fee: 1 * 1000 = 1000
        assertEquals(1000, result.network());
        // service fee: 499000000
        assertEquals(499000000, result.service());
        // total fee: 1000 + 0 + 1000 + 499000000 = 499002000
        assertEquals(499002000, result.total());
    }

    @Test
    void prepareHandlesMissingExtrasAndServicesSections() throws Exception {
        final var jsonSchedule = SimpleFeesJsonLoader.fromString(
                """
            {
              "node": { "baseFee": 1000 },
              "network": { "multiplier": 1 }
            }
            """);

        final var prepared = SimpleFeesReferenceTestCalculator.prepare(jsonSchedule);

        // Assert node base fee
        assertEquals(1000, prepared.nodeBase());
        // Assert network multiplier
        assertEquals(1, prepared.networkMultiplier());
        // Assert empty extras map
        assertEquals(0, prepared.priceByExtra().size());
        // Assert empty services map
        assertEquals(0, prepared.serviceBaseByApi().size());
    }

    @Test
    void prepareHandlesEmptyExtrasAndServices() throws Exception {
        final var jsonSchedule = SimpleFeesJsonLoader.fromString(
                """
            {
              "node": { "baseFee": 1000 },
              "network": { "multiplier": 1 },
              "extras": [],
              "services": []
            }
            """);

        final var prepared = SimpleFeesReferenceTestCalculator.prepare(jsonSchedule);

        // Assert node base fee
        assertEquals(1000, prepared.nodeBase());
        // Assert network multiplier
        assertEquals(1, prepared.networkMultiplier());
        // Assert empty extras map
        assertEquals(0, prepared.priceByExtra().size());
        // Assert empty services map
        assertEquals(0, prepared.serviceBaseByApi().size());
    }

    @Test
    void prepareBuildsExpectedNodeAndServiceMapsIgnoringUnknownExtras() throws Exception {
        final var jsonSchedule = SimpleFeesJsonLoader.fromString(
                """
            {
              "node": {
                "baseFee": 1000,
                "extras": [ { "name": "SIGNATURES", "includedCount": 1 } ]
              },
              "network": { "multiplier": 2 },
              "extras": [
                { "name": "SIGNATURES",  "fee": 100000 },
                { "name": "WEIRD_EXTRA", "fee": 999999 }
              ],
              "services": []
            }
            """);

        final var prepared = SimpleFeesReferenceTestCalculator.prepare(jsonSchedule);

        // Assert existing extras
        assertEquals(100000, prepared.priceByExtra().get(Extra.SIGNATURES));
        // Unknown extras are ignored
        assertNull(prepared.priceByExtra().get(Extra.BYTES));
        assertEquals(1, prepared.priceByExtra().size());
    }

    @Test
    void prepareBuildsExpectedNodeAndServiceMapsIgnoringUnknownOperations() throws Exception {
        final var jsonSchedule = SimpleFeesJsonLoader.fromString(
                """
            {
              "node": { "baseFee": 1000 },
              "network": { "multiplier": 1 },
              "extras": [],
              "services": [
                {
                  "name": "Crypto",
                  "schedule": [
                    {
                      "name": "WeirdOperation",
                      "baseFee": 12345,
                      "extras": []
                    }
                  ]
                }
              ]
            }
            """);

        final var prepared = SimpleFeesReferenceTestCalculator.prepare(jsonSchedule);

        // Assert nothing is mapped to CRYPTO_CREATE
        assertNull(prepared.serviceBaseByApi().get(CRYPTO_CREATE));
        // Assert the map is empty
        assertEquals(0, prepared.serviceBaseByApi().size());
    }

    @Test
    void computeWithMissingNetworkSectionDefaultsToZeroNetworkFee() throws Exception {
        final var jsonSchedule = SimpleFeesJsonLoader.fromString(
                """
            {
              "node": { "baseFee": 1000 },
              "extras": [],
              "services": [
                {
                  "name": "Crypto",
                  "schedule": [
                    {
                      "name": "CryptoCreate",
                      "baseFee": 499000000,
                      "extras": []
                    }
                  ]
                }
              ]
            }
            """);

        final var prepared = SimpleFeesReferenceTestCalculator.prepare(jsonSchedule);

        final Map<Extra, Long> extrasCount = SimpleFeesParams.create().get();
        final var result = compute(prepared, CRYPTO_CREATE, extrasCount);

        // node fee: 1000
        assertEquals(1000, result.node());
        // network fee: 0 (defaulted)
        assertEquals(0, result.network());
        // service fee: 499000000
        assertEquals(499000000, result.service());
        // total fee: 1000 + 0 + 499000000 = 499001000
        assertEquals(499001000, result.total());
    }

    @Test
    void computeWithMissingNodeSectionDefaultsToZeroNodeBaseAndIncludedExtras() throws Exception {
        final var jsonSchedule = SimpleFeesJsonLoader.fromString(
                """
            {
              "network": { "multiplier": 2 },
              "extras": [
                { "name": "SIGNATURES", "fee": 100000 }
              ],
              "services": [
                {
                  "name": "Crypto",
                  "schedule": [
                    {
                      "name": "CryptoCreate",
                      "baseFee": 1000,
                      "extras": []
                    }
                  ]
                }
              ]
            }
            """);

        final var prepared = SimpleFeesReferenceTestCalculator.prepare(jsonSchedule);

        final Map<Extra, Long> extrasCount =
                SimpleFeesParams.create().signatures(2L).get();
        final var result = compute(prepared, CRYPTO_CREATE, extrasCount);

        // node fee: 0 + (2 * 100000) = 200000
        assertEquals(200000, result.node());
        // node extras: (2 * 100000) = 200000
        assertEquals(200000, result.nodeExtras());
        // network fee: 2 * 200000 = 400000
        assertEquals(400000, result.network());
        // service fee: 1000 + 2 * 100000 = 201000
        assertEquals(201000, result.service());
        // total fee: 200000 + 400000 + 201000 = 801000
        assertEquals(801000, result.total());
    }

    @Test
    void safeAddAndMultiplyOnOverflow() throws Exception {
        final var jsonSchedule = SimpleFeesJsonLoader.fromString(
                """
        {
          "node": { "baseFee": 9223372036854775800 },
          "network": { "multiplier": 10 },
          "extras": [
            { "name": "SIGNATURES", "fee": 9223372036854775800 }
          ],
          "services": []
        }
        """);

        final var prepared = SimpleFeesReferenceTestCalculator.prepare(jsonSchedule);

        final Map<Extra, Long> extrasCount =
                SimpleFeesParams.create().signatures(2L).get();

        final var result = compute(prepared, CRYPTO_CREATE, extrasCount);

        // node, network, total should be Long.MAX_VALUE
        assertEquals(Long.MAX_VALUE, result.node());
        assertEquals(Long.MAX_VALUE, result.network());
        assertEquals(Long.MAX_VALUE, result.total());
    }
}

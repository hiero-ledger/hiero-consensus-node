// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create;

import static com.hedera.hapi.node.base.TokenSupplyType.FINITE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EXPECTED_FIXED_CUSTOM_FEES;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.TransactionBody;
import java.math.BigInteger;
import java.util.List;

public class CreateTestHelper {

    public static final Tuple CREATE_FUNGIBLE_V1_TUPLE = Tuple.of(
            Tuple.from(
                    "name",
                    "symbol",
                    NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    "memo",
                    true,
                    1000L,
                    false,
                    new Tuple[] {},
                    Tuple.of(0L, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, 0L)),
            BigInteger.valueOf(10L),
            BigInteger.valueOf(5L));

    public static final Tuple CREATE_FUNGIBLE_V2_TUPLE = Tuple.of(
            Tuple.from(
                    "name",
                    "symbol",
                    NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    "memo",
                    true,
                    1000L,
                    false,
                    new Tuple[] {},
                    Tuple.of(0L, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, 0L)),
            BigInteger.valueOf(10L),
            5L);

    public static final Tuple CREATE_FUNGIBLE_V3_TUPLE = Tuple.of(
            Tuple.from(
                    "name",
                    "symbol",
                    NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    "memo",
                    true,
                    1000L,
                    false,
                    new Tuple[] {},
                    Tuple.of(0L, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, 0L)),
            10L,
            5);

    public static final Tuple CREATE_FUNGIBLE_WITH_META_TUPLE = Tuple.of(
            Tuple.from(
                    "name",
                    "symbol",
                    NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    "memo",
                    true,
                    1000L,
                    false,
                    new Tuple[] {},
                    Tuple.of(0L, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, 0L),
                    "metadata".getBytes()),
            10L,
            5);

    public static final Tuple CREATE_FUNGIBLE_WITH_FEES_V1_TUPLE = Tuple.of(
            Tuple.from(
                    "name",
                    "symbol",
                    NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    "memo",
                    true,
                    1000L,
                    false,
                    new Tuple[] {},
                    Tuple.of(0L, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, 0L)),
            BigInteger.valueOf(10L),
            BigInteger.valueOf(5L),
            EXPECTED_FIXED_CUSTOM_FEES.toArray(Tuple[]::new),
            new Tuple[] {});

    public static final Tuple CREATE_FUNGIBLE_WITH_FEES_V2_TUPLE = Tuple.of(
            Tuple.from(
                    "name",
                    "symbol",
                    NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    "memo",
                    true,
                    1000L,
                    false,
                    new Tuple[] {},
                    Tuple.of(0L, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, 0L)),
            BigInteger.valueOf(10L),
            5L,
            EXPECTED_FIXED_CUSTOM_FEES.toArray(Tuple[]::new),
            new Tuple[] {});

    public static final Tuple CREATE_FUNGIBLE_WITH_FEES_V3_TUPLE = Tuple.of(
            Tuple.from(
                    "name",
                    "symbol",
                    NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    "memo",
                    true,
                    1000L,
                    false,
                    new Tuple[] {},
                    Tuple.of(0L, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, 0L)),
            10L,
            5,
            EXPECTED_FIXED_CUSTOM_FEES.toArray(Tuple[]::new),
            new Tuple[] {});

    public static final Tuple CREATE_FUNGIBLE_WITH_META_AND_FEES_TUPLE = Tuple.of(
            Tuple.from(
                    "name",
                    "symbol",
                    NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    "memo",
                    true,
                    1000L,
                    false,
                    new Tuple[] {},
                    Tuple.of(0L, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, 0L),
                    "metadata".getBytes()),
            10L,
            5,
            EXPECTED_FIXED_CUSTOM_FEES.toArray(Tuple[]::new),
            new Tuple[] {});

    public static final Tuple CREATE_NON_FUNGIBLE_V1_TUPLE = Tuple.singleton(Tuple.from(
            "name",
            "symbol",
            NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
            "memo",
            true,
            0L,
            false,
            new Tuple[] {},
            Tuple.of(0L, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, 0L)));

    public static final Tuple CREATE_NON_FUNGIBLE_V2_TUPLE = Tuple.singleton(Tuple.from(
            "name",
            "symbol",
            NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
            "memo",
            true,
            1000L,
            false,
            new Tuple[] {},
            Tuple.of(0L, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, 0L)));

    public static final Tuple CREATE_NON_FUNGIBLE_V3_TUPLE = Tuple.singleton(Tuple.from(
            "name",
            "symbol",
            NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
            "memo",
            true,
            1000L,
            false,
            new Tuple[] {},
            Tuple.of(0L, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, 0L)));

    public static final Tuple CREATE_NON_FUNGIBLE_WITH_META_TUPLE = Tuple.singleton(Tuple.from(
            "name",
            "symbol",
            NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
            "memo",
            true,
            1000L,
            false,
            new Tuple[] {},
            Tuple.of(0L, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, 0L),
            "metadata".getBytes()));

    public static final Tuple CREATE_NON_FUNGIBLE_WITH_FEES_V1_TUPLE = Tuple.of(
            Tuple.from(
                    "name",
                    "symbol",
                    NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    "memo",
                    true,
                    1000L,
                    false,
                    new Tuple[] {},
                    Tuple.of(0L, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, 0L)),
            EXPECTED_FIXED_CUSTOM_FEES.toArray(Tuple[]::new),
            new Tuple[] {});

    public static final Tuple CREATE_NON_FUNGIBLE_WITH_FEES_V2_TUPLE = Tuple.of(
            Tuple.from(
                    "name",
                    "symbol",
                    NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    "memo",
                    true,
                    1000L,
                    false,
                    new Tuple[] {},
                    Tuple.of(0L, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, 0L)),
            EXPECTED_FIXED_CUSTOM_FEES.toArray(Tuple[]::new),
            new Tuple[] {});

    public static final Tuple CREATE_NON_FUNGIBLE_WITH_FEES_V3_TUPLE = Tuple.of(
            Tuple.from(
                    "name",
                    "symbol",
                    NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    "memo",
                    true,
                    1000L,
                    false,
                    new Tuple[] {},
                    Tuple.of(0L, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, 0L)),
            EXPECTED_FIXED_CUSTOM_FEES.toArray(Tuple[]::new),
            new Tuple[] {});

    public static final Tuple CREATE_NON_FUNGIBLE_WITH_META_AND_FEES_TUPLE = Tuple.of(
            Tuple.from(
                    "name",
                    "symbol",
                    NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    "memo",
                    true,
                    1000L,
                    false,
                    new Tuple[] {},
                    Tuple.of(0L, NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, 0L),
                    "metadata".getBytes()),
            EXPECTED_FIXED_CUSTOM_FEES.toArray(Tuple[]::new),
            new Tuple[] {});

    public static void tokenAssertions(final TransactionBody transaction) {
        assertThat(transaction).isNotNull();
        final var tokenCreation = transaction.tokenCreation();
        assertNotNull(tokenCreation);
        assertEquals(10L, tokenCreation.initialSupply());
        assertEquals(5, tokenCreation.decimals());
        assertEquals("name", tokenCreation.name());
        assertEquals("symbol", tokenCreation.symbol());
        assertEquals(SENDER_ID, tokenCreation.treasury());
        assertEquals("memo", tokenCreation.memo());
        assertFalse(tokenCreation.freezeDefault());
        assertEquals(1000L, tokenCreation.maxSupply());
        assertEquals(FINITE, tokenCreation.supplyType());
        assertEquals(TokenType.FUNGIBLE_COMMON, tokenCreation.tokenType());
    }

    public static void nftAssertions(final TransactionBody transaction) {
        assertThat(transaction).isNotNull();
        final var tokenCreation = transaction.tokenCreation();
        assertNotNull(tokenCreation);
        assertEquals("name", tokenCreation.name());
        assertEquals("symbol", tokenCreation.symbol());
        assertEquals(SENDER_ID, tokenCreation.treasury());
        assertEquals("memo", tokenCreation.memo());
        assertFalse(tokenCreation.freezeDefault());
        assertEquals(0L, tokenCreation.initialSupply());
        assertEquals(TokenType.NON_FUNGIBLE_UNIQUE, tokenCreation.tokenType());
    }

    public static void customFeesAssertions(final List<CustomFee> customFees) {
        assertThatList(customFees).size().isEqualTo(2);
        final var customFee1 = customFees.get(0);
        assertTrue(customFee1.hasFixedFee());
        assertEquals(2L, customFee1.fixedFee().amount());
        assertNull(customFee1.fixedFee().denominatingTokenId());
        assertEquals(SENDER_ID, customFee1.feeCollectorAccountId());
        final var customFee2 = customFees.get(1);
        assertTrue(customFee2.hasFixedFee());
        assertEquals(3L, customFee2.fixedFee().amount());
        assertEquals(FUNGIBLE_TOKEN_ID, customFee2.fixedFee().denominatingTokenId());
        assertEquals(SENDER_ID, customFee2.feeCollectorAccountId());
    }
}

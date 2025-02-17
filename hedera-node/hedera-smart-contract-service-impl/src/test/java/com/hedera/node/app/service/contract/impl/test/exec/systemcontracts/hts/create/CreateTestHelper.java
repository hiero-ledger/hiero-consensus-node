/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EXPECTED_FIXED_CUSTOM_FEES;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS;

import com.esaulpaugh.headlong.abi.Tuple;
import java.math.BigInteger;

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
}

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

package com.hedera.node.app.service.token.impl.handlers.transfer.customfees;

import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.node.app.service.token.impl.test.handlers.util.TokenHandlerTestBase;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustomFeeExemptionsTest extends TokenHandlerTestBase {

    private Token treasuryToken;
    private Token nonTreasuryToken;
    private final TokenID tokenId = TokenID.newBuilder().tokenNum(1000).build();
    private final AccountID feeCollector = asAccount(0L, 0L, 7001);
    private final AccountID minter = asAccount(0L, 0L, 2001);
    private final AccountID sender = asAccount(0L, 0L, 4001);
    private final CustomFee customFee = CustomFee.newBuilder()
            .fixedFee(fixedFee)
            .feeCollectorAccountId(feeCollector)
            .fractionalFee(fractionalFee)
            .royaltyFee(royaltyFee)
            .build();

    private final CustomFee customFeeCollectorSender = CustomFee.newBuilder()
            .fixedFee(fixedFee)
            .feeCollectorAccountId(sender)
            .fractionalFee(fractionalFee)
            .royaltyFee(royaltyFee)
            .build();

    @BeforeEach
    void setUp() {
        treasuryToken = Token.newBuilder()
                .tokenId(tokenId)
                .treasuryAccountId(sender)
                .customFees(customFee)
                .tokenType(TokenType.FUNGIBLE_COMMON)
                .build();
        nonTreasuryToken = Token.newBuilder()
                .tokenId(tokenId)
                .treasuryAccountId(feeCollector)
                .customFees(customFee)
                .tokenType(TokenType.FUNGIBLE_COMMON)
                .build();
    }

    @Test
    void testCoverageOfPrivateConstructor()
            throws NoSuchMethodException, InstantiationException, IllegalAccessException {
        final Constructor<CustomFeeExemptions> constructor = CustomFeeExemptions.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        try {
            constructor.newInstance();
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertThat(cause.getClass()).isEqualTo(UnsupportedOperationException.class);
        }
    }

    @Test
    void testPayerExemptWhenPayerIsTokenTreasury() {
        assertThat(CustomFeeExemptions.isPayerExempt(treasuryToken, customFee, sender))
                .isTrue();
    }

    @Test
    void testPayerExemptWhenPayerIsTheFeeCollector() {
        assertThat(CustomFeeExemptions.isPayerExempt(treasuryToken, customFeeCollectorSender, sender))
                .isTrue();
    }

    @Test
    void testSomePayersExemptWhenPayerCollectorForAnyFee() {
        final var customFee1 = CustomFee.newBuilder()
                .feeCollectorAccountId(feeCollector)
                .fixedFee(fixedFee)
                .build();
        final var customFee2 = CustomFee.newBuilder()
                .feeCollectorAccountId(sender)
                .fixedFee(fixedFee)
                .build();
        final var token = Token.newBuilder()
                .customFees(customFee1, customFee2)
                .treasuryAccountId(minter)
                .tokenId(tokenId)
                .tokenType(TokenType.FUNGIBLE_COMMON)
                .build();
        assertThat(CustomFeeExemptions.isPayerExempt(token, customFeeCollectorSender, sender))
                .isTrue();
    }

    @Test
    void testAllPayersExemptWhenPayerCollectorForAnyFee() {
        final var customFee1 = CustomFee.newBuilder()
                .allCollectorsAreExempt(true)
                .feeCollectorAccountId(sender)
                .fixedFee(fixedFee)
                .build();
        final var customFee2 =
                CustomFee.newBuilder().allCollectorsAreExempt(true).build();

        final var customFeeCollector = CustomFee.newBuilder()
                .feeCollectorAccountId(feeCollector)
                .allCollectorsAreExempt(true)
                .fixedFee(fixedFee)
                .build();
        final var token = Token.newBuilder()
                .customFees(customFee1, customFee2)
                .treasuryAccountId(minter)
                .tokenId(tokenId)
                .tokenType(TokenType.FUNGIBLE_COMMON)
                .build();
        assertThat(CustomFeeExemptions.isPayerExempt(token, customFeeCollector, sender))
                .isTrue();
    }

    @Test
    void testAllPayersExemptButPayerNotCollectorForAnyFee() {
        final var customFee =
                CustomFee.newBuilder().allCollectorsAreExempt(true).build();
        final var customFeeCollector = CustomFee.newBuilder()
                .feeCollectorAccountId(feeCollector)
                .allCollectorsAreExempt(true)
                .fixedFee(fixedFee)
                .build();
        final var token = Token.newBuilder()
                .customFees(customFee)
                .treasuryAccountId(minter)
                .tokenId(tokenId)
                .tokenType(TokenType.FUNGIBLE_COMMON)
                .build();
        assertThat(CustomFeeExemptions.isPayerExempt(token, customFeeCollector, sender))
                .isFalse();
    }

    @Test
    void testPayerNotExemptWhenPayerIsNotCollectorForAnyFee() {
        final var customFeeCollector = CustomFee.newBuilder()
                .feeCollectorAccountId(feeCollector)
                .fixedFee(fixedFee)
                .build();
        final var token = Token.newBuilder()
                .customFees(customFeeCollector)
                .treasuryAccountId(minter)
                .tokenId(tokenId)
                .tokenType(TokenType.FUNGIBLE_COMMON)
                .build();
        assertThat(CustomFeeExemptions.isPayerExempt(token, customFeeCollector, sender))
                .isFalse();
    }

    @Test
    void testAllCollectorsExemptAndPayerNotCollectorForAnyTokenFee() {
        final var customFee1 = CustomFee.newBuilder()
                .allCollectorsAreExempt(true)
                .feeCollectorAccountId(minter)
                .fixedFee(fixedFee)
                .build();
        final var customFee2 =
                CustomFee.newBuilder().allCollectorsAreExempt(true).build();

        final var customFeeCollector = CustomFee.newBuilder()
                .feeCollectorAccountId(feeCollector)
                .fixedFee(fixedFee)
                .build();
        final var token = Token.newBuilder()
                .customFees(customFee1, customFee2)
                .treasuryAccountId(minter)
                .tokenId(tokenId)
                .tokenType(TokenType.FUNGIBLE_COMMON)
                .build();
        assertThat(CustomFeeExemptions.isPayerExempt(token, customFeeCollector, sender))
                .isFalse();
    }
}

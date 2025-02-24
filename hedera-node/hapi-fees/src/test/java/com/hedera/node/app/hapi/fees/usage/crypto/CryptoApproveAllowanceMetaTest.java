// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import static com.hedera.node.app.hapi.fees.test.IdUtils.asAccount;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoContextUtils.convertToCryptoMap;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoContextUtils.convertToNftMap;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoContextUtils.convertToTokenMap;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoContextUtils.countSerials;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.CRYPTO_ALLOWANCE_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.NFT_ALLOWANCE_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.TOKEN_ALLOWANCE_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.token.CryptoAllowance;
import com.hedera.hapi.node.token.CryptoApproveAllowanceTransactionBody;
import com.hedera.hapi.node.token.NftAllowance;
import com.hedera.hapi.node.token.TokenAllowance;
import com.hedera.node.app.hapi.fees.test.IdUtils;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CryptoApproveAllowanceMetaTest {
    private final AccountID proxy = asAccount("0.0.1234");
    private final CryptoAllowance cryptoAllowances =
            CryptoAllowance.newBuilder().spender(proxy).amount(10L).build();
    private final TokenAllowance tokenAllowances = TokenAllowance.newBuilder()
            .spender(proxy)
            .amount(10L)
            .tokenId(IdUtils.asToken("0.0.1000"))
            .build();
    private final NftAllowance nftAllowances = NftAllowance.newBuilder()
            .spender(proxy)
            .tokenId(IdUtils.asToken("0.0.1000"))
            .serialNumbers(List.of(1L, 2L, 3L))
            .build();
    private Map<Long, Long> cryptoAllowancesMap = new HashMap<>();
    private Map<AllowanceId, Long> tokenAllowancesMap = new HashMap<>();
    private Set<AllowanceId> nftAllowancesMap = new HashSet<>();

    @BeforeEach
    void setUp() {
        cryptoAllowancesMap = convertToCryptoMap(List.of(cryptoAllowances));
        tokenAllowancesMap = convertToTokenMap(List.of(tokenAllowances));
        nftAllowancesMap = convertToNftMap(List.of(nftAllowances));
    }

    @Test
    void allGettersAndToStringWork() {
        final var expected = "CryptoApproveAllowanceMeta{cryptoAllowances={1234=10},"
                + " tokenAllowances={AllowanceId[tokenNum=1000, spenderNum=1234]=10},"
                + " nftAllowances=[AllowanceId[tokenNum=1000, spenderNum=1234]],"
                + " effectiveNow=1234567, msgBytesUsed=112}";
        final var now = 1_234_567;
        final var subject = CryptoApproveAllowanceMeta.newBuilder()
                .msgBytesUsed(112)
                .cryptoAllowances(cryptoAllowancesMap)
                .tokenAllowances(tokenAllowancesMap)
                .nftAllowances(nftAllowancesMap)
                .effectiveNow(now)
                .build();

        assertEquals(now, subject.getEffectiveNow());
        assertEquals(112, subject.getMsgBytesUsed());
        assertEquals(expected, subject.toString());
    }

    @Test
    void calculatesBaseSizeAsExpected() {
        final var op = CryptoApproveAllowanceTransactionBody.newBuilder()
                .cryptoAllowances(List.of(cryptoAllowances))
                .tokenAllowances(List.of(tokenAllowances))
                .nftAllowances(List.of(nftAllowances))
                .build();

        final var subject = new CryptoApproveAllowanceMeta(op, 0);

        final var expectedMsgBytes = (op.cryptoAllowances().size() * CRYPTO_ALLOWANCE_SIZE)
                + (op.tokenAllowances().size() * TOKEN_ALLOWANCE_SIZE)
                + (op.nftAllowances().size() * NFT_ALLOWANCE_SIZE)
                + countSerials(op.nftAllowances()) * LONG_SIZE;

        assertEquals(expectedMsgBytes, subject.getMsgBytesUsed());

        final var expectedCryptoMap = new HashMap<>();
        final var expectedTokenMap = new HashMap<>();
        final var expectedNfts = new HashSet<>();
        expectedCryptoMap.put(proxy.accountNum(), 10L);
        expectedTokenMap.put(new AllowanceId(1000L, proxy.accountNum()), 10L);
        expectedNfts.add(new AllowanceId(1000L, proxy.accountNum()));
        assertEquals(expectedCryptoMap, subject.getCryptoAllowances());
        assertEquals(expectedTokenMap, subject.getTokenAllowances());
        assertEquals(expectedNfts, subject.getNftAllowances());
    }

    @Test
    void hashCodeAndEqualsWork() {
        final var now = 1_234_567;
        final var subject1 = CryptoApproveAllowanceMeta.newBuilder()
                .msgBytesUsed(112)
                .cryptoAllowances(cryptoAllowancesMap)
                .tokenAllowances(tokenAllowancesMap)
                .nftAllowances(nftAllowancesMap)
                .effectiveNow(now)
                .build();

        final var subject2 = CryptoApproveAllowanceMeta.newBuilder()
                .msgBytesUsed(112)
                .cryptoAllowances(cryptoAllowancesMap)
                .tokenAllowances(tokenAllowancesMap)
                .nftAllowances(nftAllowancesMap)
                .effectiveNow(now)
                .build();

        assertEquals(subject1, subject2);
        assertEquals(subject1.hashCode(), subject2.hashCode());
    }
}

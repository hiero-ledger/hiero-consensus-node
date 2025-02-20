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

import com.hedera.node.app.hapi.fees.test.IdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoAllowance;
import com.hederahashgraph.api.proto.java.CryptoApproveAllowanceTransactionBody;
import com.hederahashgraph.api.proto.java.NftAllowance;
import com.hederahashgraph.api.proto.java.TokenAllowance;
import com.hederahashgraph.api.proto.java.TransactionBody;
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
            CryptoAllowance.newBuilder().setSpender(proxy).setAmount(10L).build();
    private final TokenAllowance tokenAllowances = TokenAllowance.newBuilder()
            .setSpender(proxy)
            .setAmount(10L)
            .setTokenId(IdUtils.asToken("0.0.1000"))
            .build();
    private final NftAllowance nftAllowances = NftAllowance.newBuilder()
            .setSpender(proxy)
            .setTokenId(IdUtils.asToken("0.0.1000"))
            .addAllSerialNumbers(List.of(1L, 2L, 3L))
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
                .addAllCryptoAllowances(List.of(cryptoAllowances))
                .addAllTokenAllowances(List.of(tokenAllowances))
                .addAllNftAllowances(List.of(nftAllowances))
                .build();
        final var canonicalTxn =
                TransactionBody.newBuilder().setCryptoApproveAllowance(op).build();

        final var subject = new CryptoApproveAllowanceMeta(
                op, canonicalTxn.getTransactionID().getTransactionValidStart().getSeconds());

        final var expectedMsgBytes = (op.getCryptoAllowancesCount() * CRYPTO_ALLOWANCE_SIZE)
                + (op.getTokenAllowancesCount() * TOKEN_ALLOWANCE_SIZE)
                + (op.getNftAllowancesCount() * NFT_ALLOWANCE_SIZE)
                + countSerials(op.getNftAllowancesList()) * LONG_SIZE;

        assertEquals(expectedMsgBytes, subject.getMsgBytesUsed());

        final var expectedCryptoMap = new HashMap<>();
        final var expectedTokenMap = new HashMap<>();
        final var expectedNfts = new HashSet<>();
        expectedCryptoMap.put(proxy.getAccountNum(), 10L);
        expectedTokenMap.put(new AllowanceId(1000L, proxy.getAccountNum()), 10L);
        expectedNfts.add(new AllowanceId(1000L, proxy.getAccountNum()));
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

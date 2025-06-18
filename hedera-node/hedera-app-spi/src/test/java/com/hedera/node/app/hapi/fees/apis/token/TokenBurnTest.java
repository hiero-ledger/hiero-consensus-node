package com.hedera.node.app.hapi.fees.apis.token;

import com.hedera.node.app.hapi.fees.FeeResult;
import com.hedera.node.app.hapi.fees.apis.common.FTOrNFT;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenBurnTest {

    @Test
    void testTokenBurnSingle() {
        TokenBurn topic = new TokenBurn();
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("fungibleOrNonFungible", FTOrNFT.NonFungible);
        params.put("numTokens", 1);

        FeeResult fee = topic.computeFee(params);
        assertEquals(0.001, fee.fee, "Token Burn");
    }

    @Test
    void testTokenBurnMultipleFungible() {
        TokenBurn topic = new TokenBurn();
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("fungibleOrNonFungible", FTOrNFT.Fungible);
        params.put("numTokens", 10);

        FeeResult fee = topic.computeFee(params);
        assertEquals(0.001, fee.fee, "Token Burn Fungible - 10");
    }
    @Test
    void testTokenBurnMultipleNonFungible() {
        TokenBurn topic = new TokenBurn();
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("fungibleOrNonFungible", FTOrNFT.NonFungible);
        params.put("numTokens", 10);

        FeeResult fee = topic.computeFee(params);
        assertEquals(0.001 * 10, fee.fee, "Token Burn Non Fungible - 10");
    }

    @Test
    void testTokenBurnMultipleWithMultipleSignatures() {
        TokenBurn topic = new TokenBurn();
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 5);
        params.put("fungibleOrNonFungible", FTOrNFT.NonFungible);
        params.put("numTokens", 10);

        FeeResult fee = topic.computeFee(params);
        assertEquals(0.001 * 10 + 4 * 0.0001, fee.fee, "Token Burn - 10 with multiple signatures");
    }

}

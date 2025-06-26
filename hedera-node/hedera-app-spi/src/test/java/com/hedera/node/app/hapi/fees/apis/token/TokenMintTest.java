package com.hedera.node.app.hapi.fees.apis.token;

import com.hedera.node.app.hapi.fees.apis.common.FTOrNFT;
import com.hedera.node.app.hapi.fees.apis.MockExchangeRate;
import com.hedera.node.app.spi.fees.Fees;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenMintTest {

    @Test
    void testTokenFTMint() {
        TokenMint topic = new TokenMint();
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("fungibleOrNonFungible", FTOrNFT.Fungible);
        params.put("numTokens", 10);

        Fees fee = topic.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(0.001, fee.usd(), "Fungible Token Mint");
    }

    @Test
    void testTokenNFTMintOne() {
        TokenMint topic = new TokenMint();
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("fungibleOrNonFungible", FTOrNFT.NonFungible);
        params.put("numTokens", 1);

        Fees fee = topic.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(0.02, fee.usd(), "Non Fungible Token Mint - 1");
    }

    @Test
    void testTokenNFTMintMultiple() {
        TokenMint topic = new TokenMint();
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("fungibleOrNonFungible", FTOrNFT.NonFungible);
        params.put("numTokens", 10);

        Fees fee = topic.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(0.2, fee.usd(), "Non Fungible Token Mint - 10");
    }


    @Test
    void testTokenMintWithMultipleSignatures() {
        TokenMint topic = new TokenMint();
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 6);
        params.put("fungibleOrNonFungible", FTOrNFT.NonFungible);
        params.put("numTokens", 1);
        Fees fee = topic.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(0.0205, fee.usd(), "NFT mint with multiple signatures");
    }
}

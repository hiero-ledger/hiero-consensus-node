package com.hedera.node.app.hapi.fees.apis.crypto;

import com.hedera.node.app.hapi.fees.FeeCheckResult;
import com.hedera.node.app.hapi.fees.apis.MockExchangeRate;
import com.hedera.node.app.spi.fees.Fees;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CryptoTransferTest {

    @Test
    void testSimpleHbarTransfer() {
        CryptoTransfer transfer = new CryptoTransfer("Crypto", "CryptoTransfer");
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numAccountsInvolved", 2);
        Fees fee = transfer.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(0.0001, fee.usd(), "Simple hbar transfer");
    }

    @Test
    void testMultipleHbarTransfer() {
        CryptoTransfer transfer = new CryptoTransfer("Crypto", "CryptoTransfer");
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numAccountsInvolved", 5);
        Fees fee = transfer.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(0.00013 /* 0.0001 + (3 * 0.00001) */, fee.usd(), "Multiple hbar transfer");
    }

    @Test
    void testTokenTransfer() {
        CryptoTransfer transfer = new CryptoTransfer("Crypto", "CryptoTransfer");
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numAccountsInvolved", 5);
        params.put("numFTNoCustomFeeEntries", 1);

        Fees fee = transfer.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(   (3 * 0.00001) + 0.001, fee.usd(), "Simple token transfer");
    }

    @Test
    void testMultipleTokenTransfer() {
        CryptoTransfer transfer = new CryptoTransfer("Crypto", "CryptoTransfer");
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numAccountsInvolved", 10);
        params.put("numFTNoCustomFeeEntries", 5);

        Fees fee = transfer.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals( (8 * 0.00001) + (5 * 0.001), fee.usd(),"Multiple token transfers");
    }

    @Test
    void testMultipleHbarAndMultipleTokenTransfer() {
        CryptoTransfer transfer = new CryptoTransfer("Crypto", "CryptoTransfer");
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numAccountsInvolved", 10);
        params.put("numFTNoCustomFeeEntries", 5);

        Fees fee = transfer.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals((8 * 0.00001) + (5 * 0.001), fee.usd(),"Multiple hbar and token transfers");
    }

    @Test
    void testInvalidParamsFailCheck() {
        CryptoTransfer transfer = new CryptoTransfer("Crypto", "CryptoTransfer");
        Map<String, Object> params = new HashMap<>();
        params.put("numAccountsInvolved", 0);
        params.put("numFTNoCustomFeeEntries", 0);
        params.put("numNFTNoCustomFeeEntries", 0);
        params.put("numFTWithCustomFeeEntries", 0);
        params.put("numNFTWithCustomFeeEntries", 0);
        params.put("numAutoAssociationsCreated", 0);
        params.put("numAutoAccountsCreated", 0);
        params.put("numSignatures", 1);

        FeeCheckResult result = transfer.checkParameters(params);
        assertFalse(result.result, "Expected parameters to fail due to minimum transfer entry rule");
    }
}

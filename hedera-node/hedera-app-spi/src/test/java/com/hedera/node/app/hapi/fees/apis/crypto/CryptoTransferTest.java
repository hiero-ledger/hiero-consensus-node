package com.hedera.node.app.hapi.fees.apis.crypto;

import com.hedera.node.app.hapi.fees.FeeCheckResult;
import com.hedera.node.app.hapi.fees.FeeResult;
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
        FeeResult fee = transfer.computeFee(params);
        assertEquals(0.0001, fee.fee, "Simple hbar transfer");
    }

    @Test
    void testMultipleHbarTransfer() {
        CryptoTransfer transfer = new CryptoTransfer("Crypto", "CryptoTransfer");
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numAccountsInvolved", 5);
        FeeResult fee = transfer.computeFee(params);
        assertEquals(0.00013 /* 0.0001 + (3 * 0.00001) */, fee.fee, "Multiple hbar transfer");
    }

    @Test
    void testTokenTransfer() {
        CryptoTransfer transfer = new CryptoTransfer("Crypto", "CryptoTransfer");
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numAccountsInvolved", 5);
        params.put("numFTNoCustomFeeEntries", 1);

        FeeResult fee = transfer.computeFee(params);
        assertEquals(   (3 * 0.00001) + 0.001, fee.fee, "Simple token transfer");
    }

    @Test
    void testMultipleTokenTransfer() {
        CryptoTransfer transfer = new CryptoTransfer("Crypto", "CryptoTransfer");
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numAccountsInvolved", 10);
        params.put("numFTNoCustomFeeEntries", 5);

        FeeResult fee = transfer.computeFee(params);
        assertEquals( (8 * 0.00001) + (5 * 0.001), fee.fee,"Multiple token transfers");
    }

    @Test
    void testMultipleHbarAndMultipleTokenTransfer() {
        CryptoTransfer transfer = new CryptoTransfer("Crypto", "CryptoTransfer");
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numAccountsInvolved", 10);
        params.put("numFTNoCustomFeeEntries", 5);

        FeeResult fee = transfer.computeFee(params);
        assertEquals((8 * 0.00001) + (5 * 0.001), fee.fee,"Multiple hbar and token transfers");
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

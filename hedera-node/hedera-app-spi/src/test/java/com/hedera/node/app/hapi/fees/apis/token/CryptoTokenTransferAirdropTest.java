package com.hedera.node.app.hapi.fees.apis.token;

import com.hedera.node.app.hapi.fees.BaseFeeRegistry;
import com.hedera.node.app.hapi.fees.FeeResult;
import com.hedera.node.app.hapi.fees.apis.crypto.CryptoTransfer;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CryptoTokenTransferAirdropTest {
    List<TransferTestScenario> scenarios = List.of(
            // Either Crypto or TokenTransfer with no tokens should default to CryptoTransfer price
            new TransferTestScenario("CryptoTransfer", 1, 2, 0, 0, 0, 0, 0, 0, BaseFeeRegistry.getBaseFee("CryptoTransfer")),
            new TransferTestScenario("TokenTransfer", 1, 2, 0, 0, 0, 0, 0, 0, BaseFeeRegistry.getBaseFee("CryptoTransfer")),

            // Either Crypto or TokenTransfer with tokens should default to TokenTransfer price
            new TransferTestScenario("CryptoTransfer", 1, 2, 1, 0, 0, 0, 0, 0, BaseFeeRegistry.getBaseFee("TokenTransfer")),
            new TransferTestScenario("TokenTransfer", 1, 2, 1, 0, 0, 0, 0, 0, BaseFeeRegistry.getBaseFee("TokenTransfer")),

            // Either Crypto or TokenTransfer with tokens without custom fees should charge for those while giving one token transfer free
            new TransferTestScenario("CryptoTransfer", 1, 2, 2, 0, 0, 0, 0, 0, BaseFeeRegistry.getBaseFee("TokenTransfer") + BaseFeeRegistry.getBaseFee("TokenTransfer")),
            new TransferTestScenario("CryptoTransfer", 1, 2, 0, 2, 0, 0, 0, 0, BaseFeeRegistry.getBaseFee("TokenTransfer")+ BaseFeeRegistry.getBaseFee("TokenTransfer")),

            new TransferTestScenario("TokenTransfer", 1, 2, 10, 0, 0, 0, 0, 0, BaseFeeRegistry.getBaseFee("TokenTransfer")+ 9 * BaseFeeRegistry.getBaseFee("TokenTransfer")),
            new TransferTestScenario("TokenTransfer", 1, 2, 0, 10, 0, 0, 0, 0, BaseFeeRegistry.getBaseFee("TokenTransfer")+ 9 * BaseFeeRegistry.getBaseFee("TokenTransfer")),
            new TransferTestScenario("TokenAirdrop", 1, 2, 0, 10, 0, 0, 0, 0, BaseFeeRegistry.getBaseFee("TokenAirdrop")+ 9 * BaseFeeRegistry.getBaseFee("TokenTransfer")),

            // Any API involving one token with custom fees should include that in the base price
            new TransferTestScenario("CryptoTransfer", 1, 2, 0, 0, 1, 0, 0, 0, BaseFeeRegistry.getBaseFee("TokenTransferWithCustomFee")),
            new TransferTestScenario("TokenTransfer", 1, 2, 0, 0, 1, 0, 0, 0, BaseFeeRegistry.getBaseFee("TokenTransferWithCustomFee")),
            new TransferTestScenario("TokenAirdrop", 1, 2, 0, 0, 1, 0, 0, 0, BaseFeeRegistry.getBaseFee("TokenAirdropWithCustomFee")),

            // Any API involving more than one token with custom fees should include the custom fee token in the base price, and include other tokens
            new TransferTestScenario("CryptoTransfer", 1, 2, 1, 1, 2, 4, 0, 0, BaseFeeRegistry.getBaseFee("TokenTransferWithCustomFee") + 2 * BaseFeeRegistry.getBaseFee("TokenTransfer") + 5 * BaseFeeRegistry.getBaseFee("TokenTransferWithCustomFee")),
            new TransferTestScenario("TokenTransfer", 1, 2, 1, 1, 2, 4, 0, 0, BaseFeeRegistry.getBaseFee("TokenTransferWithCustomFee") + 2 * BaseFeeRegistry.getBaseFee("TokenTransfer") + 5 * BaseFeeRegistry.getBaseFee("TokenTransferWithCustomFee")),
            new TransferTestScenario("TokenAirdrop", 1, 2, 1, 1, 2, 4, 0, 0, BaseFeeRegistry.getBaseFee("TokenAirdropWithCustomFee") + 2 * BaseFeeRegistry.getBaseFee("TokenTransfer") + 5 * BaseFeeRegistry.getBaseFee("TokenTransferWithCustomFee")),

            // Every API should charge overages for signatures, accounts, auto-associations, and auto-account-creations
            new TransferTestScenario("CryptoTransfer", 5, 6, 1, 0, 0, 0, 3, 4, BaseFeeRegistry.getBaseFee("TokenTransfer")  + 4 * BaseFeeRegistry.getBaseFee("PerSignature") + 4 * BaseFeeRegistry.getBaseFee("PerCryptoTransferAccount") + 3 * BaseFeeRegistry.getBaseFee("TokenAssociateToAccount") + 4 * BaseFeeRegistry.getBaseFee("CryptoCreate")),
            new TransferTestScenario("TokenTransfer", 5, 6, 1, 0, 0, 0, 3, 4, BaseFeeRegistry.getBaseFee("TokenTransfer")  + 4 * BaseFeeRegistry.getBaseFee("PerSignature") + 4 * BaseFeeRegistry.getBaseFee("PerCryptoTransferAccount") + 3 * BaseFeeRegistry.getBaseFee("TokenAssociateToAccount") + 4 * BaseFeeRegistry.getBaseFee("CryptoCreate")),
            new TransferTestScenario("TokenAirdrop", 5, 6, 1, 0, 0, 0, 3, 4, BaseFeeRegistry.getBaseFee("TokenAirdrop") + 4 * BaseFeeRegistry.getBaseFee("PerSignature") + 4 * BaseFeeRegistry.getBaseFee("PerCryptoTransferAccount") + 3 * BaseFeeRegistry.getBaseFee("TokenAssociateToAccount") + 4 * BaseFeeRegistry.getBaseFee("CryptoCreate"))

    );

    @Test
    void testPredefinedScenarios() {
        for (var scenario: scenarios) {
            CryptoTransfer transfer = switch (scenario.api) {
                case "CryptoTransfer" -> new CryptoTransfer("Crypto", "CryptoTransfer");
                case "TokenTransfer" -> new CryptoTransfer("Token", "TokenTransfer");
                case "TokenAirdrop" -> new CryptoTransfer("Crypto", "TokenAirdrop");
                default -> throw new IllegalStateException("Unexpected value: " + scenario.api);
            };

            Map<String, Object> params = new HashMap<>();
            params.put("numSignatures", scenario.numSignatures);
            params.put("numAccountsInvolved", scenario.numAccountsInvolved);
            params.put("numFTNoCustomFeeEntries", scenario.numFTNoCustomFeeEntries);
            params.put("numNFTNoCustomFeeEntries", scenario.numNFTNoCustomFeeEntries);
            params.put("numFTWithCustomFeeEntries", scenario.numFTWithCustomFeeEntries);
            params.put("numNFTWithCustomFeeEntries", scenario.numNFTWithCustomFeeEntries);
            params.put("numAutoAssociationsCreated", scenario.numAutoAssociationsCreated);
            params.put("numAutoAccountsCreated", scenario.numAutoAccountsCreated);

            FeeResult fee = transfer.computeFee(params);
            assertEquals(scenario.expectedFee, fee.fee, 1e-9, "hbar/token/airdrop test: " + scenario);
        }
    }
}

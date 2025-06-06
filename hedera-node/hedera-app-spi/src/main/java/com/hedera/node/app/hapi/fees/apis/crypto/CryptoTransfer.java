package com.hedera.node.app.hapi.fees.apis.crypto;

import com.hedera.node.app.hapi.fees.*;

import java.util.List;
import java.util.Map;

import static com.hedera.node.app.hapi.fees.apis.common.FeeConstants.TOKEN_FREE_TOKENS;

public class CryptoTransfer extends AbstractFeeModel {
    String service;
    String api;

    private final List<ParameterDefinition> params = List.of(
            new ParameterDefinition("numAccountsInvolved", "number", null, 2, 0, 20, "Number of Accounts involved"),
            new ParameterDefinition("numFTNoCustomFeeEntries", "number", null, 0, 0, 10, "Fungible token entries without custom fee"),
            new ParameterDefinition("numNFTNoCustomFeeEntries", "number", null, 0, 0, 10, "NFT entries without custom fee"),
            new ParameterDefinition("numFTWithCustomFeeEntries", "number", null, 0, 0, 10, "Fungible token entries with custom fee"),
            new ParameterDefinition("numNFTWithCustomFeeEntries", "number", null, 0, 0, 10, "NFT entries with custom fee"),
            new ParameterDefinition("numAutoAssociationsCreated", "number", null, 0, 0, 10, "Auto-created token associations"),
            new ParameterDefinition("numAutoAccountsCreated", "number", null, 0, 0, 20, "Auto-created accounts")
    );

    public CryptoTransfer(String service, String api) {
        this.service = service;
        this.api = api;
    }

    @Override
    public String getService() {
        return service;
    }

    @Override
    public String getDescription() {
        return "Transfers a combination of hbars and tokens.";
    }

    @Override
    protected List<ParameterDefinition> apiSpecificParams() {
        return params;
    }

    @Override
    public FeeCheckResult checkParameters(Map<String, Object> values) {
        FeeCheckResult base = super.checkParameters(values);
        if (!base.result) return base;

        if ( (int) values.get("numAccountsInvolved") < 2 ||
                ((int) values.get("numFTNoCustomFeeEntries") < 2 &&
                (int) values.get("numNFTNoCustomFeeEntries") < 2 &&
                (int) values.get("numNFTWithCustomFeeEntries") < 2)) {
            return FeeCheckResult.failure("There must be at least 2 entries of hbar or token transfers.");
        }

        return FeeCheckResult.success();
    }

    private int getInt(Object value) {
        return (value instanceof Integer) ? (Integer) value : 0;
    }

    // This API is complex. This same code can be called for
    // 1. CryptoTransfer: just hbar transfers. The base fee here includes 1 hbar transfer entry between 2 accounts
    // 2. TokenTransfer: fungible and/or non-fungible tokens and/or hbars. These tokens could have custom fee enabled. The base fee here includes 1 FT/NFT token transfer without custom fee
    // 3. TokenAirdrop: fungible and/or non-fungible tokens. These tokens could have custom fee enabled. The base fee here includes 1 FT/NFT token transfer without custom fee
    protected FeeResult computeApiSpecificFee(Map<String, Object> values) {
        FeeResult fee = new FeeResult();

        // Extract values
        int ftNoCustom = getInt(values.get("numFTNoCustomFeeEntries"));
        int nftNoCustom = getInt(values.get("numNFTNoCustomFeeEntries"));
        int ftWithCustom = getInt(values.get("numFTWithCustomFeeEntries"));
        int nftWithCustom = getInt(values.get("numNFTWithCustomFeeEntries"));
        int customTokens = ftWithCustom + nftWithCustom;
        boolean tokenTransfersPresent = (ftNoCustom + nftNoCustom + ftWithCustom + nftWithCustom) > 0;

        int numFreeTokens = TOKEN_FREE_TOKENS;
        String effectiveApi = api;

        // If no token transfers are present, then treat it as CryptoTransfer (hbar transfer). Otherwise, treat it as a TokenTransfer
        if (tokenTransfersPresent) {
            if (api.equals("CryptoTransfer")) {
                effectiveApi = "TokenTransfer";
            }
        } else {
            effectiveApi = "CryptoTransfer";
        }

        // If tokens with custom fees are used, then use the higher base prices
        if (customTokens > 0) {
            if (effectiveApi.equals("TokenTransfer")) {
                fee.addDetail("Base fee for " + effectiveApi + " (with Custom fee)", 1,  BaseFeeRegistry.getBaseFee("TokenTransferWithCustomFee"));
            } else if (effectiveApi.equals("TokenAirdrop")) {
                fee.addDetail("Base fee for " + effectiveApi + " (with Custom fee)", 1,  BaseFeeRegistry.getBaseFee("TokenAirdropWithCustomFee"));
            }
        } else {
            fee.addDetail("Base fee for " + effectiveApi, 1, BaseFeeRegistry.getBaseFee(effectiveApi));
        }

        // Overage for the number of accounts that we need to update for handling this transaction
        if (values.get("numAccountsInvolved") instanceof Integer num && num > 2)
            fee.addDetail("Accounts involved", (num - 2), (num - 2) * BaseFeeRegistry.getBaseFee("PerCryptoTransferAccount"));

        // Overage for the number of token-types that we need to fetch for handling this transaction
        // Process the tokens with Custom Fee first since we have already increased the base price to accommodate the presence of custom-fee tokens, and the included free token should count against the token with custom fee
        if (ftWithCustom > 0) {
            if ((ftWithCustom - numFreeTokens) > 0) {
                fee.addDetail("FT with custom fee", (ftWithCustom - numFreeTokens), (ftWithCustom - numFreeTokens) * BaseFeeRegistry.getBaseFee("TokenTransferWithCustomFee"));
            }
            numFreeTokens = 0;
        }
        if (nftWithCustom > 0) {
            if ((nftWithCustom - numFreeTokens) > 0) {
                fee.addDetail("NFT with custom fee", (nftWithCustom - numFreeTokens), (nftWithCustom - numFreeTokens) * BaseFeeRegistry.getBaseFee("TokenTransferWithCustomFee"));
            }
            numFreeTokens = 0;
        }
        if (ftNoCustom > 0) {
            if ((ftNoCustom - numFreeTokens) > 0) {
                fee.addDetail("FT no custom fee", (ftNoCustom - numFreeTokens), (ftNoCustom - numFreeTokens) * BaseFeeRegistry.getBaseFee("TokenTransfer"));
            }
            numFreeTokens = 0;
        }
        if (nftNoCustom > 0) {
            if ((nftNoCustom - numFreeTokens) > 0) {
                fee.addDetail("NFT no custom fee", (nftNoCustom - numFreeTokens), (nftNoCustom - numFreeTokens) * BaseFeeRegistry.getBaseFee("TokenTransfer"));
            }
        }

        // Overage for the number of entities created automatically (associations/accounts) during handling this transaction
        if (values.get("numAutoAssociationsCreated") instanceof Integer num && num > 0)
            fee.addDetail("Auto token associations", num, num * BaseFeeRegistry.getBaseFee("TokenAssociateToAccount"));
        if (values.get("numAutoAccountsCreated") instanceof Integer num && num > 0)
            fee.addDetail("Auto account creations", num, num * BaseFeeRegistry.getBaseFee("CryptoCreate"));

        return fee;
    }
}

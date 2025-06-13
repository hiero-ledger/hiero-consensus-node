package com.hedera.node.app.hapi.fees.apis.token;

import com.hedera.node.app.hapi.fees.AbstractFeeModel;
import com.hedera.node.app.hapi.fees.BaseFeeRegistry;
import com.hedera.node.app.hapi.fees.FeeResult;
import com.hedera.node.app.hapi.fees.ParameterDefinition;
import com.hedera.node.app.hapi.fees.apis.common.FTOrNFT;

import java.util.List;
import java.util.Map;

import static com.hedera.node.app.hapi.fees.apis.common.FTOrNFT.NonFungible;

public class TokenMint extends AbstractFeeModel {
    private final List<ParameterDefinition> params = List.of(
            new ParameterDefinition("fungibleOrNonFungible", "list", new Object[] {FTOrNFT.Fungible, FTOrNFT.NonFungible}, FTOrNFT.Fungible, 0, 0, "Fungible or Non-fungible token"),
            new ParameterDefinition("numTokens", "number", null, 1, 1, 10, "Number of tokens minted")
    );

    @Override
    public String getService() {
        return "Token";
    }

    @Override
    public String getDescription() {
        return "Mint tokens";
    }

    @Override
    protected List<ParameterDefinition> apiSpecificParams() {
        return params;
    }

    @Override
    protected FeeResult computeApiSpecificFee(Map<String, Object> values) {
        FeeResult fee = new FeeResult();

        final FTOrNFT fungibleOrNonFungible = (FTOrNFT) values.get("fungibleOrNonFungible");

        double baseFeeForMint = fungibleOrNonFungible == FTOrNFT.Fungible ? BaseFeeRegistry.getBaseFee("TokenMintFungible"): BaseFeeRegistry.getBaseFee("TokenMintNonFungible");
        fee.addDetail("Base fee", 1, baseFeeForMint);

        int numTokens = (int) values.get("numTokens");
        final int numFreeTokens = 1;
        if (fungibleOrNonFungible == FTOrNFT.NonFungible && numTokens > numFreeTokens) {
            fee.addDetail("Additional NFTs", numTokens - numFreeTokens, (numTokens - numFreeTokens) * baseFeeForMint);
        }

        return fee;
    }
}

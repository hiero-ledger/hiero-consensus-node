package com.hedera.node.app.hapi.fees.apis.token;

import com.hedera.node.app.hapi.fees.AbstractFeeModel;
import com.hedera.node.app.hapi.fees.BaseFeeRegistry;
import com.hedera.node.app.hapi.fees.FeeResult;
import com.hedera.node.app.hapi.fees.ParameterDefinition;
import com.hedera.node.app.hapi.fees.apis.common.FTOrNFT;

import java.util.List;
import java.util.Map;

import static com.hedera.node.app.hapi.fees.apis.common.FTOrNFT.NonFungible;

public class TokenBurn extends AbstractFeeModel {
    private final List<ParameterDefinition> params = List.of(
            new ParameterDefinition("fungibleOrNonFungible", "list", new Object[] {FTOrNFT.Fungible, NonFungible}, FTOrNFT.Fungible, 0, 0, "Fungible or Non-fungible token"),
            new ParameterDefinition("numTokens", "number", null, 1, 1, 10, "Number of NFT serials burned")
    );

    @Override
    public String getService() {
        return "Token";
    }

    @Override
    public String getDescription() {
        return "Burn tokens";
    }

    @Override
    protected List<ParameterDefinition> apiSpecificParams() {
        return params;
    }

    @Override
    protected FeeResult computeApiSpecificFee(Map<String, Object> values) {
        FeeResult fee = new FeeResult();

        fee.addDetail("Base fee", 1, BaseFeeRegistry.getBaseFee("TokenBurn"));

        final FTOrNFT fungibleOrNonFungible = (FTOrNFT) values.get("fungibleOrNonFungible");
        if (fungibleOrNonFungible == NonFungible) {
            int numTokens = (int) values.get("numTokens");
            final int numFreeTokens = 1;
            if (numTokens > numFreeTokens) {
                fee.addDetail("Additional NFTs", numTokens - numFreeTokens, (numTokens - numFreeTokens) * BaseFeeRegistry.getBaseFee("TokenBurn"));
            }
        }

        return fee;
    }
}

package com.hedera.node.app.hapi.fees.apis.token;

import com.hedera.node.app.hapi.fees.AbstractFeeModel;
import com.hedera.node.app.hapi.fees.BaseFeeRegistry;
import com.hedera.node.app.hapi.fees.FeeResult;
import com.hedera.node.app.hapi.fees.ParameterDefinition;

import java.util.List;
import java.util.Map;

public class TokenWipe extends AbstractFeeModel {
    private final List<ParameterDefinition> params = List.of(
            new ParameterDefinition("numTokens", "number", null, 1, 1, 10, "If NFT, Number of serials wiped")
    );

    @Override
    public String getService() {
        return "Token";
    }

    @Override
    public String getDescription() {
        return "Wipe tokens";
    }

    @Override
    protected List<ParameterDefinition> apiSpecificParams() {
        return params;
    }

    @Override
    protected FeeResult computeApiSpecificFee(Map<String, Object> values) {
        FeeResult fee = new FeeResult();

        double baseFeeForWipe = BaseFeeRegistry.getBaseFee("TokenAccountWipe");
        fee.addDetail("Base fee", 1, baseFeeForWipe);

        int numTokens = (int) values.get("numTokens");
        final int numFreeTokens = 1;
        if (numTokens > numFreeTokens) {
            fee.addDetail("Additional NFTs", numTokens - numFreeTokens, (numTokens - numFreeTokens) * baseFeeForWipe);
        }

        return fee;
    }
}

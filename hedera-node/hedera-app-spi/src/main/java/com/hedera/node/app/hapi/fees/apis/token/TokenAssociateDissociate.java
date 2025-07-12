package com.hedera.node.app.hapi.fees.apis.token;

import com.hedera.node.app.hapi.fees.AbstractFeeModel;
import com.hedera.node.app.hapi.fees.BaseFeeRegistry;
import com.hedera.node.app.hapi.fees.FeeResult;
import com.hedera.node.app.hapi.fees.ParameterDefinition;
import com.hedera.node.app.hapi.fees.apis.common.AssociateOrDissociate;

import java.util.List;
import java.util.Map;

public class TokenAssociateDissociate extends AbstractFeeModel {
    AssociateOrDissociate associateOrDissociate;

    private final List<ParameterDefinition> params = List.of(
            new ParameterDefinition("numTokenTypes", "number", null, 1, 1, 10, "Number of associated token-types")
    );

    public TokenAssociateDissociate(AssociateOrDissociate associateOrDissociate) {
        this.associateOrDissociate = associateOrDissociate;
    }

    @Override
    public String getService() {
        return "Token";
    }

    @Override
    public String getDescription() {
        return (this.associateOrDissociate == AssociateOrDissociate.Associate) ? "Associate tokens-types to accounts" : "Dissociate tokens-types from accounts";
    }

    @Override
    protected List<ParameterDefinition> apiSpecificParams() {
        return params;
    }

    @Override
    protected FeeResult computeApiSpecificFee(Map<String, Object> values) {
        FeeResult fee = new FeeResult();

        double baseFee = ((AssociateOrDissociate)values.get("associateOrDissociate") == AssociateOrDissociate.Associate) ?
                BaseFeeRegistry.getBaseFee("TokenAssociateToAccount") : BaseFeeRegistry.getBaseFee("TokenDissociateFromAccount");

        fee.addDetail("Base fee", 1, baseFee);

        int numTokenTypes = (int) values.get("numTokenTypes");
        final int numFreeTokenTypes = 1;
        if (numTokenTypes > numFreeTokenTypes) {
            fee.addDetail("Additional token-types", numTokenTypes - numFreeTokenTypes, (numTokenTypes - numFreeTokenTypes) * baseFee);
        }

        return fee;
    }
}

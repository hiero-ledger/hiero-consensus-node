package com.hedera.node.app.hapi.fees.apis.crypto;

import com.hedera.node.app.hapi.fees.AbstractFeeModel;
import com.hedera.node.app.hapi.fees.BaseFeeRegistry;
import com.hedera.node.app.hapi.fees.FeeResult;
import com.hedera.node.app.hapi.fees.ParameterDefinition;

import java.util.List;
import java.util.Map;

import static com.hedera.node.app.hapi.fees.apis.common.FeeConstants.*;

public class CryptoAllowance extends AbstractFeeModel {
    String api;
    String description;

    private final List<ParameterDefinition> params = List.of(
            new ParameterDefinition("numAllowances", "number", null, MIN_ALLOWANCES, MIN_ALLOWANCES, MAX_ALLOWANCES, "Number of Allowances")
    );

    public CryptoAllowance(String api, String description) {
        this.api = api;
        this.description = description;
    }

    @Override
    public String getService() {
        return "Crypto";
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    protected List<ParameterDefinition> apiSpecificParams() {
        return params;
    }

    @Override
    protected FeeResult computeApiSpecificFee(Map<String, Object> values) {
        FeeResult fee = new FeeResult();
        fee.addDetail("Base fee", 1, BaseFeeRegistry.getBaseFee(api));

        int numAllowances = (int) values.get("numAllowances");
        if (numAllowances > FREE_ALLOWANCES) {
            fee.addDetail("Additional allowances", (numAllowances - FREE_ALLOWANCES), (numAllowances - FREE_ALLOWANCES) * BaseFeeRegistry.getBaseFee(api));
        }
        return fee;
    }
}

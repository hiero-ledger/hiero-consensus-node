package com.hedera.node.app.hapi.fees.apis.contract;

import com.hedera.node.app.hapi.fees.BaseFeeRegistry;
import com.hedera.node.app.hapi.fees.FeeResult;
import com.hedera.node.app.hapi.fees.ParameterDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.hedera.node.app.hapi.fees.apis.common.FeeConstants.MAX_KEYS;
import static com.hedera.node.app.hapi.fees.apis.common.FeeConstants.MIN_KEYS;

public class ContractCreate extends ContractBasedOnGas {
    private final List<ParameterDefinition> params = List.of(
            new ParameterDefinition("numKeys", "number", null, MIN_KEYS, MIN_KEYS, MAX_KEYS, "Number of keys")
    );

    public ContractCreate(String api, String description, boolean isMinGasFree) {
        super(api, description, isMinGasFree);
    }


    @Override
    public String getDescription() {
        return "Create a new contract";
    }

    @Override
    protected List<ParameterDefinition> apiSpecificParams() {
        List<ParameterDefinition> p = new ArrayList<>(super.apiSpecificParams());
        p.addAll(params);
        return p;
    }

    @Override
    protected FeeResult computeApiSpecificFee(Map<String, Object> values) {
        FeeResult fee = super.computeApiSpecificFee(values);
//        fee.addDetail("Base fee", 1, BaseFeeRegistry.getBaseFee(api));

        int numKeys = (int) values.get("numKeys");

        if (numKeys > 1) {
            fee.addDetail("Additional keys", numKeys - 1, (numKeys - 1) * BaseFeeRegistry.getBaseFee("PerKey"));
        }
        return fee;
    }
}

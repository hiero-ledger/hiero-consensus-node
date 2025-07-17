package com.hedera.node.app.hapi.fees.apis.contract;

import com.hedera.node.app.hapi.fees.AbstractFeeModel;
import com.hedera.node.app.hapi.fees.BaseFeeRegistry;
import com.hedera.node.app.hapi.fees.FeeResult;
import com.hedera.node.app.hapi.fees.ParameterDefinition;

import java.util.List;
import java.util.Map;

import static com.hedera.node.app.hapi.fees.apis.common.FeeConstants.*;

public class ContractBasedOnGas extends AbstractFeeModel {
    String api;
    String description;
    boolean isMinGasFree;

    public ContractBasedOnGas(String api, String description, boolean isMinGasFree) {
        this.api = api;
        this.description = description;
        this.isMinGasFree = isMinGasFree;
    }

    private final List<ParameterDefinition> params = List.of(
            new ParameterDefinition("gas", "number", null, MIN_GAS, MIN_GAS, MAX_GAS, "Gas")
    );

    @Override
    public String getService() {
        return "Smart Contract";
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
        int gas = (int) values.get("gas");
        int gasTobeCharged = Math.max((gas - (isMinGasFree ? MIN_GAS : 0)), 0);
        if (gasTobeCharged > 0) {
            fee.addDetail("Additional Gas fee", (gasTobeCharged), (gasTobeCharged) * BaseFeeRegistry.getBaseFee("PerGas"));
        }
        return fee;
    }
}

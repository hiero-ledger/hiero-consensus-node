package com.hedera.node.app.hapi.fees.apis.common;

import com.hedera.node.app.hapi.fees.AbstractFeeModel;
import com.hedera.node.app.hapi.fees.BaseFeeRegistry;
import com.hedera.node.app.hapi.fees.FeeResult;
import com.hedera.node.app.hapi.fees.ParameterDefinition;

import java.util.List;
import java.util.Map;


public class NoParametersAPI extends AbstractFeeModel {
    String service;
    String api;
    String description;
    public NoParametersAPI(String service, String api, String description) {
        this.service = service;
        this.api = api;
        this.description = description;
    }

    @Override
    public String getService() { return service; }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    protected List<ParameterDefinition> apiSpecificParams() {
        return List.of();
    }

    @Override
    protected FeeResult computeApiSpecificFee(Map<String, Object> values) {
        FeeResult fee = new FeeResult();
        fee.addDetail("Base fee", 1, BaseFeeRegistry.getBaseFee(api));
        return fee;
    }
}

package com.hedera.node.app.hapi.fees.apis.file;

import com.hedera.node.app.hapi.fees.AbstractFeeModel;
import com.hedera.node.app.hapi.fees.BaseFeeRegistry;
import com.hedera.node.app.hapi.fees.FeeResult;
import com.hedera.node.app.hapi.fees.ParameterDefinition;

import java.util.List;
import java.util.Map;

import static com.hedera.node.app.hapi.fees.apis.common.FeeConstants.*;


public class FileOperations extends AbstractFeeModel {
    String api;
    String description;

    private final List<ParameterDefinition> params = List.of(
            new ParameterDefinition("numKeys", "number", null,MIN_KEYS, MIN_KEYS, MAX_KEYS, "Number of keys"),
            new ParameterDefinition("numBytes", "number", null, FILE_FREE_BYTES, FILE_MIN_BYTES, FILE_MAX_BYTES, "Size of the file (bytes)")
    );

    public FileOperations( String api, String description) {
        this.api = api;
        this.description = description;
    }

    @Override
    public String getService() { return "File"; }

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

        int numKeys = (int) values.get("numKeys");
        int numFreeKeys = 1;
        if (numKeys > numFreeKeys) {
            fee.addDetail("Additional keys", numKeys - numFreeKeys, (numKeys - numFreeKeys) * BaseFeeRegistry.getBaseFee("PerKey"));
        }

        int numBytes = (int) values.get("numBytes");
        if (numBytes > FILE_FREE_BYTES) {
            fee.addDetail("Additional file size", (numBytes - FILE_FREE_BYTES), (numBytes - FILE_FREE_BYTES) * BaseFeeRegistry.getBaseFee("PerFileByte"));
        }
        System.out.println("total fee is " + fee.toString());
        return fee;
    }
}

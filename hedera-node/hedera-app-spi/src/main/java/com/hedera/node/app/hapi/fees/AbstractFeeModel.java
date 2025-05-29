package com.hedera.node.app.hapi.fees;

import com.hedera.node.app.hapi.fees.apis.common.AssociateOrDissociate;
import com.hedera.node.app.hapi.fees.apis.common.FTOrNFT;
import com.hedera.node.app.hapi.fees.apis.common.YesOrNo;

import java.util.*;

import static com.hedera.node.app.hapi.fees.apis.common.FeeConstants.MAX_SIGNATURES;
import static com.hedera.node.app.hapi.fees.apis.common.FeeConstants.MIN_SIGNATURES;

public abstract class AbstractFeeModel {
    int numFreeSignatures = 1;

    protected static final List<ParameterDefinition> COMMON_PARAMS = List.of(
            new ParameterDefinition("numSignatures", "number", null,MIN_SIGNATURES, MIN_SIGNATURES, MAX_SIGNATURES, "Executed Signatures Verifications count")
    );

    // Returns the description of the API
    public abstract String getService();

    // Returns the description of the API
    public abstract String getDescription();

    protected abstract List<ParameterDefinition> apiSpecificParams();

    // Get the list of parameters that are relevant for the specified API
    public List<ParameterDefinition> getParameters() {
        List<ParameterDefinition> merged = new ArrayList<>(COMMON_PARAMS);
        merged.addAll(apiSpecificParams());
        return merged;
    }

    // Check the values of parameters based on the specified API
    public FeeCheckResult checkParameters(Map<String, Object> values) {
        for (ParameterDefinition p : getParameters()) {
            if ("number".equals(p.type)) {
                try {
                    Object value = values.get(p.name);
                    if (value == null) {
                        continue;
                    }
                    int val = (int) value;
                    if (val < p.min || val > p.max) {
                        return FeeCheckResult.failure("Parameter " + p.name + " must be in range [" + p.min + ", " + p.max + "]");
                    }
                } catch (ClassCastException ex) {
                    return FeeCheckResult.failure("Parameter " + p.name + " must be a number");
                }
            }
        }
        return FeeCheckResult.success();
    }
    public void setNumFreeSignatures(int numFreeSignatures) {
        this.numFreeSignatures = numFreeSignatures;
    }


    // Compute the fee. There are 2 parts to the fee. There's the API specific fee (e.g. cryptoCreate price is based on the number of keys), and there's fee for parameters that are common across all APIs (e.g. number of signatures)
    public FeeResult computeFee(Map<String, Object> values) {
        preprocessEnumValues(values);

        FeeResult result = computeApiSpecificFee(values);

        // Compute the fee for parameters that are common across all APIs
        if (values.containsKey("numSignatures")) {
            final int numSignatures = (int) values.get("numSignatures");
            if (numSignatures > numFreeSignatures) {
                final int additionalSignatures = numSignatures - numFreeSignatures;
                final double fee = additionalSignatures * BaseFeeRegistry.getBaseFee("PerSignature");
                result.addDetail("Additional signature verifications", additionalSignatures, fee);
            }
        }
        return result;
    }

    // Compute API specific fee (e.g. cryptoCreate price is based on the number of keys)
    protected abstract FeeResult computeApiSpecificFee(Map<String, Object> values);

    @SuppressWarnings("unchecked")
    private void preprocessEnumValues(Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object val = entry.getValue();

            if (val instanceof String strVal) {
                try {
                    if (strVal.equalsIgnoreCase("YES") || strVal.equalsIgnoreCase("NO")) {
                        entry.setValue(YesOrNo.valueOf(strVal.toUpperCase()));
                    } else if (strVal.equalsIgnoreCase("Fungible") || strVal.equalsIgnoreCase("NonFungible")) {
                        entry.setValue(FTOrNFT.valueOf(strVal));
                    } else if (strVal.equalsIgnoreCase("Associate") || strVal.equalsIgnoreCase("Dissociate")) {
                        entry.setValue(AssociateOrDissociate.valueOf(strVal));
                    }
                } catch (IllegalArgumentException e) {
                    // Ignore or log invalid enums if desired
                }
            }
        }
    }
}


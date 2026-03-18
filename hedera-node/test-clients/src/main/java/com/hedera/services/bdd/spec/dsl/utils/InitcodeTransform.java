// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.utils;

import com.hedera.services.bdd.spec.HapiSpec;

public interface InitcodeTransform {
    class NoOp implements InitcodeTransform {
        @Override
        public String transformHexed(HapiSpec spec, String initcode) {
            return initcode;
        }
    }

    /**
     * Transforms the hexed initcode before deploying the contract.
     * @param spec the spec
     * @param initcode the initcode
     * @return the transformed initcode
     */
    String transformHexed(HapiSpec spec, String initcode);
}

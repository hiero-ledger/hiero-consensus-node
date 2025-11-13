// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.utils;

import com.hedera.services.bdd.spec.HapiSpec;
import java.util.function.BiFunction;

public interface InitcodeTransform extends BiFunction<HapiSpec, String, String> {
    class NoOp implements InitcodeTransform {
        @Override
        public String apply(HapiSpec spec, String initcode) {
            return initcode;
        }
    }
}

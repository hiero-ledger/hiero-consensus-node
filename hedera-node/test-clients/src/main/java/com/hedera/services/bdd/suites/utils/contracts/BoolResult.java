/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.services.bdd.suites.utils.contracts;

import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import org.apache.tuweni.bytes.Bytes;

public class BoolResult implements ContractCallResult {
    final boolean expected;

    public BoolResult(boolean expected) {
        this.expected = expected;
    }

    public static BoolResult flag(boolean expected) {
        return new BoolResult(expected);
    }

    @Override
    public Bytes getBytes() {
        final var boolType = TupleType.parse("(bool)");
        final var result = Tuple.singleton(expected);
        return Bytes.wrap(boolType.encode(result).array());
    }
}

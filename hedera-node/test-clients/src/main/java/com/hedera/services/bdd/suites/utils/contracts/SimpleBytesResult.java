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
import java.math.BigInteger;
import org.apache.tuweni.bytes.Bytes;

public class SimpleBytesResult implements ContractCallResult {
    BigInteger exists;

    private SimpleBytesResult(long result) {
        this.exists = BigInteger.valueOf(result);
    }

    public static SimpleBytesResult bigIntResult(long result) {
        return new SimpleBytesResult(result);
    }

    @Override
    public Bytes getBytes() {
        final var intType = TupleType.parse("(int)");
        final var result = Tuple.singleton(exists);
        return Bytes.wrap(intType.encode(result).array());
    }
}

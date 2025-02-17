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

import static com.esaulpaugh.headlong.abi.Address.toChecksumAddress;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import org.apache.tuweni.bytes.Bytes;

public class AddressResult implements ContractCallResult {
    final String hexedAddress;

    public AddressResult(final String hexedAddress) {
        this.hexedAddress = hexedAddress;
    }

    public static AddressResult hexedAddress(final String hexedAddress) {
        return new AddressResult("0x" + hexedAddress);
    }

    @Override
    public Bytes getBytes() {
        final var addressType = TupleType.parse("(address)");
        final var result = Tuple.singleton(Address.wrap(toChecksumAddress(hexedAddress)));
        return Bytes.wrap(addressType.encode(result).array());
    }
}

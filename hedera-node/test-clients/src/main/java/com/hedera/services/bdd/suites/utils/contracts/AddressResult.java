// SPDX-License-Identifier: Apache-2.0
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

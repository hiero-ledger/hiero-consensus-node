// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static org.hyperledger.besu.crypto.Hash.sha256;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;

/**
 * An {@link Account} whose code is an EIP-7702 delegation to the {@code 0x167} system contract, and thus can
 * never change its storage or nonce.
 *
 * <p>It also cannot have a non-zero balance, as dispatching a {@code transferValue()} with a token
 * address as receiver will always fail.
 *
 * <p>Despite this inherent immutability, for convenience still implements {@link MutableAccount} so
 * that instances can be used anywhere the Besu EVM needs a <i>potentially</i> mutable account.
 * Mutability should always turn out to be unnecessary in these cases, however; so the mutator methods
 * on this class do throw {@code UnsupportedOperationException} .
 */
public class TokenEvmAccount extends AbstractEvmEntityAccount {

    // EIP-7702 delegation to HTS system contract
    public static final Bytes CODE = Bytes.concatenate(
            Bytes.fromHexString("ef0100"), Bytes.fromHexString("0000000000000000000000000000000000000167"));
    public static final Hash CODE_HASH = Hash.wrap(sha256(CODE));

    public TokenEvmAccount(@NonNull final Address address, @NonNull final EvmFrameState state) {
        super(address, state);
    }

    @Override
    public boolean isTokenFacade() {
        return true;
    }

    @Override
    public Bytes getCode() {
        return CODE;
    }

    @Override
    public Hash getCodeHash() {
        return CODE_HASH;
    }
}

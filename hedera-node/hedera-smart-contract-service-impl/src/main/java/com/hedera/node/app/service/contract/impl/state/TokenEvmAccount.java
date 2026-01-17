// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.code.CodeFactory;

/**
 * An {@link Account} whose code proxies all calls to the {@code 0x167} system contract, and thus can
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

    public TokenEvmAccount(@NonNull final Address address, @NonNull final EvmFrameState state) {
        super(address, state);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isTokenFacade() {
        return true;
    }

    @Override
    public Bytes getCode() {
        return state.getTokenRedirectCode(address);
    }

    @Override
    public @NonNull Code getEvmCode(@NonNull final Bytes functionSelector, @NonNull final CodeFactory codeFactory) {
        return codeFactory.createCode(getCode(), false);
    }

    @Override
    public Hash getCodeHash() {
        return state.getTokenRedirectCodeHash(address);
    }
}

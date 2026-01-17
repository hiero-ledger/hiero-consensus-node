// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

public abstract class AbstractEvmEntityAccount extends AbstractMutableEvmAccount {
    public static final long ENTITY_PROXY_ACCOUNT_NONCE = -1;
    protected final Address address;
    protected final EvmFrameState state;

    protected AbstractEvmEntityAccount(@NonNull final Address address, @NonNull final EvmFrameState state) {
        this.address = requireNonNull(address);
        this.state = requireNonNull(state);
    }

    @Override
    public Address getAddress() {
        return address;
    }

    @Override
    public long getNonce() {
        return ENTITY_PROXY_ACCOUNT_NONCE;
    }

    @Override
    public Wei getBalance() {
        return Wei.ZERO;
    }

    @Override
    public @NonNull UInt256 getStorageValue(@NonNull final UInt256 key) {
        return UInt256.ZERO;
    }

    @Override
    public UInt256 getOriginalStorageValue(@NonNull final UInt256 key) {
        return UInt256.ZERO;
    }

    /**
     * Since an entity is not actually a native Hedera account, always throws {@link UnsupportedOperationException}.
     */
    @Override
    public com.hedera.hapi.node.state.token.Account toNativeAccount() {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isTokenFacade() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isScheduleTxnFacade() {
        return false;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public void becomeImmutable() {
        throw new UnsupportedOperationException("Not Implemented Yet");
    }

    @Override
    public void setNonce(final long value) {
        throw new UnsupportedOperationException("setNonce");
    }

    @Override
    public void setCode(@NonNull final Bytes code) {
        throw new UnsupportedOperationException("setCode");
    }

    @Override
    public void setStorageValue(@NonNull final UInt256 key, @NonNull final UInt256 value) {
        throw new UnsupportedOperationException("setStorageValue");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRegularAccount() {
        return false;
    }

    @Override
    public @NonNull AccountID hederaId() {
        throw new IllegalStateException("Entity facade has no usable Hedera id");
    }

    @Override
    public @NonNull ContractID hederaContractId() {
        return state.entityIdFactory().newContractId(numberOfLongZero(address));
    }

    @Override
    public boolean isStorageEmpty() {
        return true;
    }
}

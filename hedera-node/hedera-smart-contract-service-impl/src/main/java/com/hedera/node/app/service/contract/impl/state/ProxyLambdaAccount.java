// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.token.Account;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.code.CodeFactory;

public class ProxyLambdaAccount extends AbstractMutableEvmAccount {
    public static final Address HLS_EVM_ADDRESS = Address.fromHexString("0x16c");

    private final ContractID contractId;
    private final EvmFrameState state;

    public ProxyLambdaAccount(@NonNull final ContractID contractId, @NonNull final EvmFrameState state) {
        this.contractId = requireNonNull(contractId);
        this.state = requireNonNull(state);
    }

    @Override
    public Address getAddress() {
        return HLS_EVM_ADDRESS;
    }

    @Override
    public @NonNull ContractID hederaContractId() {
        return contractId;
    }

    @Override
    public Wei getBalance() {
        return Wei.ZERO;
    }

    @Override
    public @NonNull Code getEvmCode(@NonNull final Bytes functionSelector) {
        requireNonNull(functionSelector);
        return CodeFactory.createCode(getCode(), 0, false);
    }

    @Override
    public Bytes getCode() {
        return state.getCode(hederaContractId());
    }

    @Override
    public @NonNull Hash getCodeHash() {
        return state.getCodeHash(contractId);
    }

    @Override
    public long getNonce() {
        return 0;
    }

    @Override
    public void setNonce(final long nonce) {
        throw new AssertionError("Not implemented");
    }

    @Override
    public UInt256 getStorageValue(@NonNull final UInt256 key) {
        requireNonNull(key);
        return state.getStorageValue(contractId, key);
    }

    @Override
    public UInt256 getOriginalStorageValue(@NonNull final UInt256 key) {
        requireNonNull(key);
        return state.getOriginalStorageValue(contractId, key);
    }

    @Override
    public void setStorageValue(@NonNull final UInt256 key, @NonNull final UInt256 value) {
        requireNonNull(key);
        requireNonNull(value);
        state.setStorageValue(contractId, key, value);
    }

    @Override
    public boolean isTokenFacade() {
        return false;
    }

    @Override
    public boolean isRegularAccount() {
        return false;
    }

    @Override
    public boolean isScheduleTxnFacade() {
        return false;
    }

    @Override
    public void setCode(@NonNull final Bytes bytes) {
        throw new UnsupportedOperationException("toNativeAccount");
    }

    @Override
    public Account toNativeAccount() {
        throw new UnsupportedOperationException("toNativeAccount");
    }

    @Override
    public @NonNull AccountID hederaId() {
        throw new UnsupportedOperationException("hederaId");
    }

    @Override
    public void becomeImmutable() {
        throw new UnsupportedOperationException("becomeImmutable");
    }
}

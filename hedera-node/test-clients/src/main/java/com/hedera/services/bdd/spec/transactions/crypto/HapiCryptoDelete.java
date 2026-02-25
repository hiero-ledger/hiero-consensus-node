// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.crypto;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.queries.QueryUtils.lookUpAccountWithAlias;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.crypto.ReferenceType;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiCryptoDelete extends HapiTxnOp<HapiCryptoDelete> {
    static final Logger log = LogManager.getLogger(HapiCryptoDelete.class);

    private String account;
    private String aliasKeySource = null;
    private boolean shouldPurge = false;
    private boolean omitId = false;
    private Optional<String> transferAccount = Optional.empty();
    private ReferenceType referenceType = ReferenceType.REGISTRY_NAME;

    @Nullable
    private BiConsumer<HapiSpec, CryptoDeleteTransactionBody.Builder> explicitDef;

    public HapiCryptoDelete(String account) {
        this.account = account;
    }

    public HapiCryptoDelete(String reference, ReferenceType type) {
        this.referenceType = type;
        if (type == ReferenceType.ALIAS_KEY_NAME) {
            aliasKeySource = reference;
        } else {
            account = reference;
        }
    }

    public HapiCryptoDelete(@NonNull final BiConsumer<HapiSpec, CryptoDeleteTransactionBody.Builder> def) {
        explicitDef = requireNonNull(def);
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.CryptoDelete;
    }

    @Override
    protected HapiCryptoDelete self() {
        return this;
    }

    public HapiCryptoDelete transfer(String to) {
        transferAccount = Optional.of(to);
        return this;
    }

    public HapiCryptoDelete sansTargetId() {
        omitId = true;
        return this;
    }

    public HapiCryptoDelete purging() {
        shouldPurge = true;
        return this;
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
        return spec.fees()
                .forActivityBasedOp(
                        HederaFunctionality.CryptoDelete, cryptoFees::getCryptoDeleteTxFeeMatrices, txn, numPayerKeys);
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(HapiSpec spec) throws Throwable {
        final AccountID target;
        if (explicitDef == null) {
            if (referenceType == ReferenceType.REGISTRY_NAME) {
                target = TxnUtils.asId(account, spec);
            } else {
                account = lookUpAccountWithAlias(spec, aliasKeySource);
                target = asAccount(account);
            }
        } else {
            target = AccountID.getDefaultInstance();
        }
        CryptoDeleteTransactionBody opBody = spec.txns()
                .<CryptoDeleteTransactionBody, CryptoDeleteTransactionBody.Builder>body(
                        CryptoDeleteTransactionBody.class, b -> {
                            if (explicitDef != null) {
                                explicitDef.accept(spec, b);
                            } else {
                                transferAccount.ifPresent(a ->
                                        b.setTransferAccountID(spec.registry().getAccountID(a)));
                                if (!omitId) {
                                    b.setDeleteAccountID(target);
                                }
                            }
                        });
        return b -> b.setCryptoDelete(opBody);
    }

    @Override
    protected void updateStateOf(HapiSpec spec) throws Throwable {
        if (actualStatus != SUCCESS) {
            return;
        }
        if (shouldPurge) {
            spec.registry().removeAccount(account);
            if (spec.registry().hasKey(account)) {
                spec.registry().removeKey(account);
            }
            if (spec.registry().hasSigRequirement(account)) {
                spec.registry().removeSigRequirement(account);
            }
            if (spec.registry().hasKey(aliasKeySource)) {
                final var lookedUpKey =
                        spec.registry().getKey(aliasKeySource).toByteString().toStringUtf8();
                spec.registry().removeAccount(lookedUpKey);
                spec.registry().removeKey(lookedUpKey);
            }
        }
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        List<Function<HapiSpec, Key>> deleteSigners = new ArrayList<>();
        deleteSigners.addAll(super.defaultSigners());
        deleteSigners.add(spec -> spec.registry().getKey(account));
        deleteSigners.add(spec ->
                spec.registry().getKey(transferAccount.orElse(spec.setup().defaultTransferName())));
        return deleteSigners;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("account", account);
    }
}

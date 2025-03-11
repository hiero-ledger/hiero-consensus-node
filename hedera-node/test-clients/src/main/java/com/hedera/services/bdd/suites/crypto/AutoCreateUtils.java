// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.CRYPTO_TRANSFER_RECEIVER;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.LAZY_CREATE_SPONSOR;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.LAZY_MEMO;
import static com.hedera.services.bdd.suites.crypto.AutoAccountUpdateSuite.INITIAL_BALANCE;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.TRANSFER_TXN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.hyperledger.besu.datatypes.Address.contractAddress;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Key;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;

public class AutoCreateUtils {

    private static final String TRANSFER_TXN_FOR_CREATE_1 = "transferTxnForCreate1";

    public static ByteString randomValidEd25519Alias() {
        final var alias = RandomStringUtils.random(128, true, true);
        return Key.newBuilder()
                .setEd25519(ByteString.copyFromUtf8(alias))
                .build()
                .toByteString();
    }

    public static ByteString randomValidECDSAAlias() {
        final var alias = RandomStringUtils.random(128, true, true);
        return Key.newBuilder()
                .setECDSASecp256K1(ByteString.copyFromUtf8(alias))
                .build()
                .toByteString();
    }

    public static Key asKey(final ByteString alias) {
        Key aliasKey;
        try {
            aliasKey = Key.parseFrom(alias);
        } catch (InvalidProtocolBufferException ex) {
            return Key.newBuilder().build();
        }
        return aliasKey;
    }

    public static void updateSpecFor(HapiSpec spec, String alias) {
        var accountIDAtomicReference = new AtomicReference<AccountID>();
        allRunFor(spec, getAliasedAccountInfo(alias).exposingIdTo(accountIDAtomicReference::set));
        final var aliasKey = spec.registry().getKey(alias).toByteString().toStringUtf8();
        spec.registry().saveAccountAlias(aliasKey, accountIDAtomicReference.get());
        spec.registry().saveAccountId(alias, accountIDAtomicReference.get());
    }

    public static HapiSpecOperation[] createHollowAccountFrom(@NonNull final String key) {
        return createHollowAccountFrom(key, INITIAL_BALANCE * ONE_HBAR, ONE_HUNDRED_HBARS);
    }

    public static HapiSpecOperation[] createHollowAccountFrom(
            @NonNull final String key, final long sponsorBalance, final long transferAmount) {
        return new HapiSpecOperation[] {
            cryptoCreate(LAZY_CREATE_SPONSOR).balance(sponsorBalance),
            cryptoCreate(CRYPTO_TRANSFER_RECEIVER).balance(INITIAL_BALANCE * ONE_HBAR),
            withOpContext((spec, opLog) -> {
                final var ecdsaKey =
                        spec.registry().getKey(key).getECDSASecp256K1().toByteArray();
                final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
                final var op = cryptoTransfer(tinyBarsFromTo(LAZY_CREATE_SPONSOR, evmAddress, transferAmount))
                        .hasKnownStatus(SUCCESS)
                        .via(TRANSFER_TXN);
                final var op2 = getAliasedAccountInfo(evmAddress)
                        .has(accountWith()
                                .hasEmptyKey()
                                .expectedBalanceWithChargedUsd(transferAmount, 0, 0)
                                .autoRenew(THREE_MONTHS_IN_SECONDS)
                                .receiverSigReq(false)
                                .memo(LAZY_MEMO));

                // create a hollow account with the correct create1 address
                final var create1Address =
                        contractAddress(Address.wrap(Bytes.wrap(recoverAddressFromPubKey(ecdsaKey))), 0L);
                final var create1ByteString = ByteString.copyFrom(create1Address.toArray());

                final var op3 = cryptoTransfer(
                                tinyBarsFromTo(LAZY_CREATE_SPONSOR, create1ByteString, ONE_HUNDRED_HBARS))
                        .hasKnownStatus(SUCCESS)
                        .via(TRANSFER_TXN_FOR_CREATE_1);

                final HapiGetTxnRecord hapiGetTxnRecord =
                        getTxnRecord(TRANSFER_TXN).andAllChildRecords().logged();
                allRunFor(spec, op, op2, op3, hapiGetTxnRecord);

                final AccountID newAccountID = hapiGetTxnRecord
                        .getFirstNonStakingChildRecord()
                        .getReceipt()
                        .getAccountID();
                spec.registry().saveAccountId(key, newAccountID);
            })
        };
    }
}

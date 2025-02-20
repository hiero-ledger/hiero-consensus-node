// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.keys;

import static com.hedera.node.app.service.token.impl.test.keys.KeyTree.withRoot;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.IdUtils.asAccount;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.IdUtils.asToken;
import static com.hedera.node.app.service.token.impl.test.keys.NodeFactory.ed25519;
import static com.hedera.node.app.service.token.impl.test.keys.NodeFactory.list;
import static com.hedera.node.app.service.token.impl.test.keys.NodeFactory.threshold;

import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import java.util.stream.Stream;

public interface KeysAndIds {
    String DEFAULT_NODE_ID = "0.0.3";
    AccountID DEFAULT_NODE = asAccount(DEFAULT_NODE_ID);
    String DEFAULT_PAYER_ID = "0.0.13257";
    AccountID DEFAULT_PAYER = asAccount(DEFAULT_PAYER_ID);
    String MASTER_PAYER_ID = "0.0.50";
    AccountID MASTER_PAYER = asAccount(MASTER_PAYER_ID);
    String TREASURY_PAYER_ID = "0.0.2";
    AccountID TREASURY_PAYER = asAccount(TREASURY_PAYER_ID);
    KeyTree DEFAULT_PAYER_KT = withRoot(list(ed25519()));
    AccountID STAKING_FUND = asAccount("0.0.800");

    String CURRENTLY_UNUSED_ALIAS = "currentlyUnusedAlias";

    String NO_RECEIVER_SIG_ID = "0.0.1337";
    String NO_RECEIVER_SIG_ALIAS = "noReceiverSigReqAlias";
    AccountID NO_RECEIVER_SIG = asAccount(NO_RECEIVER_SIG_ID);
    KeyTree NO_RECEIVER_SIG_KT = withRoot(ed25519());

    String RECEIVER_SIG_ID = "0.0.1338";
    String RECEIVER_SIG_ALIAS = "receiverSigReqAlias";
    AccountID RECEIVER_SIG = asAccount(RECEIVER_SIG_ID);
    KeyTree RECEIVER_SIG_KT = withRoot(list(ed25519(), threshold(1, ed25519(), ed25519())));

    String MISC_ACCOUNT_ID = "0.0.1339";
    AccountID MISC_ACCOUNT = asAccount(MISC_ACCOUNT_ID);
    KeyTree MISC_ACCOUNT_KT = withRoot(ed25519());

    String CUSTOM_PAYER_ACCOUNT_ID = "0.0.1216";
    AccountID CUSTOM_PAYER_ACCOUNT = asAccount(CUSTOM_PAYER_ACCOUNT_ID);
    KeyTree CUSTOM_PAYER_ACCOUNT_KT = withRoot(ed25519());

    String OWNER_ACCOUNT_ID = "0.0.1439";
    AccountID OWNER_ACCOUNT = asAccount(OWNER_ACCOUNT_ID);
    KeyTree OWNER_ACCOUNT_KT = withRoot(ed25519());

    String DELEGATING_SPENDER_ID = "0.0.1539";
    AccountID DELEGATING_SPENDER = asAccount(DELEGATING_SPENDER_ID);
    KeyTree DELEGATING_SPENDER_KT = withRoot(ed25519());

    String SYS_ACCOUNT_ID = "0.0.666";
    AccountID SYS_ACCOUNT = asAccount(SYS_ACCOUNT_ID);

    String DILIGENT_SIGNING_PAYER_ID = "0.0.1340";
    AccountID DILIGENT_SIGNING_PAYER = asAccount(DILIGENT_SIGNING_PAYER_ID);
    KeyTree DILIGENT_SIGNING_PAYER_KT = withRoot(threshold(2, ed25519(true), ed25519(true), ed25519(false)));

    String TOKEN_TREASURY_ID = "0.0.1341";
    AccountID TOKEN_TREASURY = asAccount(TOKEN_TREASURY_ID);
    KeyTree TOKEN_TREASURY_KT = withRoot(threshold(2, ed25519(false), ed25519(true), ed25519(false)));

    String COMPLEX_KEY_ACCOUNT_ID = "0.0.1342";
    AccountID COMPLEX_KEY_ACCOUNT = asAccount(COMPLEX_KEY_ACCOUNT_ID);
    KeyTree COMPLEX_KEY_ACCOUNT_KT = withRoot(list(
            ed25519(),
            threshold(1, list(list(ed25519(), ed25519()), ed25519()), ed25519()),
            ed25519(),
            list(threshold(2, ed25519(), ed25519(), ed25519()))));

    String FROM_OVERLAP_PAYER_ID = "0.0.1343";
    AccountID FROM_OVERLAP_PAYER = asAccount(FROM_OVERLAP_PAYER_ID);
    KeyTree FROM_OVERLAP_PAYER_KT = withRoot(threshold(2, ed25519(true), ed25519(true), ed25519(false)));

    KeyTree SYS_ACCOUNT_KT = withRoot(list(ed25519(), threshold(1, ed25519(), ed25519())));

    Long DEFAULT_BALANCE = 1_000L;
    Long DEFAULT_PAYER_BALANCE = 1_000_000_000_000L;

    String KNOWN_TOKEN_IMMUTABLE_ID = "0.0.534";
    TokenID KNOWN_TOKEN_IMMUTABLE = asToken(KNOWN_TOKEN_IMMUTABLE_ID);
    String KNOWN_TOKEN_NO_SPECIAL_KEYS_ID = "0.0.535";
    TokenID KNOWN_TOKEN_NO_SPECIAL_KEYS = asToken(KNOWN_TOKEN_NO_SPECIAL_KEYS_ID);
    String KNOWN_TOKEN_WITH_FREEZE_ID = "0.0.777";
    TokenID KNOWN_TOKEN_WITH_FREEZE = asToken(KNOWN_TOKEN_WITH_FREEZE_ID);
    String KNOWN_TOKEN_WITH_KYC_ID = "0.0.776";
    TokenID KNOWN_TOKEN_WITH_KYC = asToken(KNOWN_TOKEN_WITH_KYC_ID);
    String KNOWN_TOKEN_WITH_SUPPLY_ID = "0.0.775";
    TokenID KNOWN_TOKEN_WITH_SUPPLY = asToken(KNOWN_TOKEN_WITH_SUPPLY_ID);
    String KNOWN_TOKEN_WITH_WIPE_ID = "0.0.774";
    TokenID KNOWN_TOKEN_WITH_WIPE = asToken(KNOWN_TOKEN_WITH_WIPE_ID);
    String KNOWN_TOKEN_WITH_FEE_SCHEDULE_ID = "0.0.779";
    TokenID KNOWN_TOKEN_WITH_FEE_SCHEDULE_KEY = asToken(KNOWN_TOKEN_WITH_FEE_SCHEDULE_ID);
    String KNOWN_TOKEN_WITH_ROYALTY_FEE_ID = "0.0.77977";
    TokenID KNOWN_TOKEN_WITH_ROYALTY_FEE_AND_FALLBACK = asToken(KNOWN_TOKEN_WITH_ROYALTY_FEE_ID);
    String KNOWN_TOKEN_WITH_PAUSE_ID = "0.0.780";
    TokenID KNOWN_TOKEN_WITH_PAUSE = asToken(KNOWN_TOKEN_WITH_PAUSE_ID);
    String DELETED_TOKEN_ID = "0.0.781";
    TokenID DELETED_TOKEN = asToken(DELETED_TOKEN_ID);

    String FIRST_TOKEN_SENDER_ID = "0.0.888";
    AccountID FIRST_TOKEN_SENDER = asAccount(FIRST_TOKEN_SENDER_ID);
    ByteString FIRST_TOKEN_SENDER_LITERAL_ALIAS = ByteString.copyFromUtf8("firstTokenSender");

    String SECOND_TOKEN_SENDER_ID = "0.0.999";
    AccountID SECOND_TOKEN_SENDER = asAccount(SECOND_TOKEN_SENDER_ID);
    String TOKEN_RECEIVER_ID = "0.0.1111";
    AccountID TOKEN_RECEIVER = asAccount(TOKEN_RECEIVER_ID);

    String UNKNOWN_TOKEN_ID = "0.0.666";
    TokenID MISSING_TOKEN = asToken(UNKNOWN_TOKEN_ID);

    KeyTree FIRST_TOKEN_SENDER_KT = withRoot(ed25519());
    KeyTree SECOND_TOKEN_SENDER_KT = withRoot(ed25519());
    KeyTree TOKEN_ADMIN_KT = withRoot(ed25519());
    KeyTree TOKEN_FEE_SCHEDULE_KT = withRoot(ed25519());
    KeyTree TOKEN_FREEZE_KT = withRoot(ed25519());
    KeyTree TOKEN_SUPPLY_KT = withRoot(ed25519());
    KeyTree TOKEN_WIPE_KT = withRoot(ed25519());
    KeyTree TOKEN_KYC_KT = withRoot(ed25519());
    KeyTree TOKEN_PAUSE_KT = withRoot(ed25519());

    class IdUtils {
        public static AccountID asAccount(String v) {
            long[] nativeParts = asDotDelimitedLongArray(v);
            return AccountID.newBuilder()
                    .setShardNum(nativeParts[0])
                    .setRealmNum(nativeParts[1])
                    .setAccountNum(nativeParts[2])
                    .build();
        }

        public static TokenID asToken(String v) {
            long[] nativeParts = asDotDelimitedLongArray(v);
            return TokenID.newBuilder()
                    .setShardNum(nativeParts[0])
                    .setRealmNum(nativeParts[1])
                    .setTokenNum(nativeParts[2])
                    .build();
        }

        static long[] asDotDelimitedLongArray(String s) {
            String[] parts = s.split("[.]");
            return Stream.of(parts).mapToLong(Long::valueOf).toArray();
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import static com.swirlds.demo.platform.PAYLOAD_CATEGORY.CATEGORY_FCM;
import static com.swirlds.demo.platform.PAYLOAD_CATEGORY.CATEGORY_FCQ;
import static com.swirlds.demo.platform.PAYLOAD_CATEGORY.CATEGORY_NONE;
import static com.swirlds.demo.platform.PAYLOAD_CATEGORY.CATEGORY_VIRTUAL_MERKLE;
import static com.swirlds.demo.platform.PAYLOAD_CATEGORY.CATEGORY_VIRTUAL_MERKLE_SMART_CONTRACT;

import com.swirlds.common.FastCopyable;
import com.swirlds.demo.platform.fs.stresstest.proto.FCMTransaction;
import java.util.HashMap;
import java.util.Map;

public enum PAYLOAD_TYPE implements FastCopyable {
    TYPE_RANDOM_BYTES(0),
    //	TYPE_TEST_PAUSE(12),
    TYPE_FCM_TEST(13),
    // FCM MapValueData
    TYPE_FCM_CREATE(14, CATEGORY_FCM),
    TYPE_FCM_UPDATE(15, CATEGORY_FCM),
    TYPE_FCM_DELETE(16, CATEGORY_FCM),
    TYPE_FCM_TRANSFER(17, CATEGORY_FCM),
    TYPE_FCM_ASSORTED(18, CATEGORY_FCM),
    TYPE_FCM_ACCOUNT_SUBSET(42, CATEGORY_FCM),

    TYPE_TEST_SYNC(31),
    TYPE_TEST_LOOP(32),

    // FCM MapValueFCQ
    TYPE_FCM_CREATE_FCQ(33, CATEGORY_FCQ),
    TYPE_FCM_UPDATE_FCQ(34, CATEGORY_FCQ),
    TYPE_FCM_DELETE_FCQ_NODE(35, CATEGORY_FCQ),
    TYPE_FCM_TRANSFER_FCQ(36, CATEGORY_FCQ),
    TYPE_FCM_CREATE_WITH_RECORDS_FCQ(37, CATEGORY_FCQ),
    TYPE_FCM_ACCOUNT_SUBSET_FCQ(43, CATEGORY_FCQ),

    // For serializing on all nodes at a consensus timestamp, in order to avoid discrepancies.
    SAVE_EXPECTED_MAP(38),
    TYPE_FCM_DELETE_FCQ(39, CATEGORY_FCQ),
    TYPE_FCM_ASSORTED_FCQ(40, CATEGORY_FCQ),

    TYPE_MINT_TOKEN(42),
    TYPE_TRANSFER_TOKEN(43),
    TYPE_BURN_TOKEN(44),

    TYPE_DUMMY_TRANSACTION(45),

    // Virtual Merkle
    TYPE_VIRTUAL_MERKLE_TEST(50),

    TYPE_VIRTUAL_MERKLE_CREATE(51, CATEGORY_VIRTUAL_MERKLE),

    TYPE_VIRTUAL_MERKLE_UPDATE(52, CATEGORY_VIRTUAL_MERKLE),

    TYPE_VIRTUAL_MERKLE_DELETE(53, CATEGORY_VIRTUAL_MERKLE),

    TYPE_VIRTUAL_MERKLE_CREATE_SMART_CONTRACT(54, CATEGORY_VIRTUAL_MERKLE_SMART_CONTRACT),

    TYPE_VIRTUAL_MERKLE_SMART_CONTRACT_METHOD_EXECUTION(55, CATEGORY_VIRTUAL_MERKLE_SMART_CONTRACT),

    // MIX FCM and Virtual Merkle transaction
    TYPE_FCM_VIRTUAL_MIX(60);

    public static Map<FCMTransaction.BodyCase, PAYLOAD_TYPE> BodyCase_TO_PAYLOAD_TYPE = new HashMap<>();

    static {

        // BodyCase Enum is part of FCMTransaction and is different from these Payload types
        // this map establishes a correspondence.
        BodyCase_TO_PAYLOAD_TYPE.put(FCMTransaction.BodyCase.CREATEACCOUNT, PAYLOAD_TYPE.TYPE_FCM_CREATE);
        BodyCase_TO_PAYLOAD_TYPE.put(FCMTransaction.BodyCase.TRANSFERBALANCE, PAYLOAD_TYPE.TYPE_FCM_TRANSFER);
        BodyCase_TO_PAYLOAD_TYPE.put(FCMTransaction.BodyCase.DELETEACCOUNT, PAYLOAD_TYPE.TYPE_FCM_DELETE);
        BodyCase_TO_PAYLOAD_TYPE.put(FCMTransaction.BodyCase.UPDATEACCOUNT, PAYLOAD_TYPE.TYPE_FCM_UPDATE);
        BodyCase_TO_PAYLOAD_TYPE.put(FCMTransaction.BodyCase.ASSORTEDACCOUNT, PAYLOAD_TYPE.TYPE_FCM_ASSORTED);
        BodyCase_TO_PAYLOAD_TYPE.put(FCMTransaction.BodyCase.ACCOUNTSUBSET, PAYLOAD_TYPE.TYPE_FCM_ACCOUNT_SUBSET);

        BodyCase_TO_PAYLOAD_TYPE.put(FCMTransaction.BodyCase.CREATEACCOUNTFCQ, PAYLOAD_TYPE.TYPE_FCM_CREATE_FCQ);
        BodyCase_TO_PAYLOAD_TYPE.put(FCMTransaction.BodyCase.TRANSFERBALANCEFCQ, PAYLOAD_TYPE.TYPE_FCM_TRANSFER_FCQ);
        BodyCase_TO_PAYLOAD_TYPE.put(FCMTransaction.BodyCase.DELETEFCQ, PAYLOAD_TYPE.TYPE_FCM_DELETE_FCQ);
        BodyCase_TO_PAYLOAD_TYPE.put(FCMTransaction.BodyCase.UPDATEACCOUNTFCQ, PAYLOAD_TYPE.TYPE_FCM_UPDATE_FCQ);
        BodyCase_TO_PAYLOAD_TYPE.put(FCMTransaction.BodyCase.ASSORTEDFCQ, PAYLOAD_TYPE.TYPE_FCM_ASSORTED_FCQ);
        BodyCase_TO_PAYLOAD_TYPE.put(
                FCMTransaction.BodyCase.ACCOUNTSUBSETFCQ, PAYLOAD_TYPE.TYPE_FCM_ACCOUNT_SUBSET_FCQ);

        BodyCase_TO_PAYLOAD_TYPE.put(FCMTransaction.BodyCase.MINTTOKEN, PAYLOAD_TYPE.TYPE_MINT_TOKEN);
        BodyCase_TO_PAYLOAD_TYPE.put(FCMTransaction.BodyCase.TRANSFERTOKEN, PAYLOAD_TYPE.TYPE_TRANSFER_TOKEN);
        BodyCase_TO_PAYLOAD_TYPE.put(FCMTransaction.BodyCase.BURNTOKEN, PAYLOAD_TYPE.TYPE_BURN_TOKEN);

        BodyCase_TO_PAYLOAD_TYPE.put(FCMTransaction.BodyCase.DUMMYTRANSACTION, PAYLOAD_TYPE.TYPE_DUMMY_TRANSACTION);
    }

    private final int value;
    private final PAYLOAD_CATEGORY payloadCategory;

    PAYLOAD_TYPE(final int value) {
        this(value, CATEGORY_NONE);
    }

    PAYLOAD_TYPE(final int value, final PAYLOAD_CATEGORY payloadCategory) {
        this.value = value;
        this.payloadCategory = payloadCategory;
    }

    private static PAYLOAD_TYPE[] allValues = values();

    public static PAYLOAD_TYPE fromOrdinal(int n) {
        return allValues[n];
    }

    public PAYLOAD_CATEGORY getPayloadCategory() {
        return payloadCategory;
    }

    @Override
    public PAYLOAD_TYPE copy() {
        throwIfImmutable();
        return this;
    }
}

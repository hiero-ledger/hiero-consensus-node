// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hederahashgraph.api.proto.java.ResponseType.ANSWER_ONLY;
import static com.hederahashgraph.api.proto.java.ResponseType.COST_ANSWER;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.QueryHeader;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ResponseHeader;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionGetReceiptQuery;
import com.hederahashgraph.api.proto.java.TransactionID;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

public class QueryUtils {

    /**
     * Private constructor to prevent instantiation.
     *
     * @throws UnsupportedOperationException if invoked by reflection or other means.
     */
    private QueryUtils() {
        throw new UnsupportedOperationException();
    }

    public static QueryHeader answerHeader(Transaction txn) {
        return QueryHeader.newBuilder()
                .setResponseType(ANSWER_ONLY)
                .setPayment(txn)
                .build();
    }

    public static QueryHeader answerCostHeader(Transaction txn) {
        return QueryHeader.newBuilder()
                .setResponseType(COST_ANSWER)
                .setPayment(txn)
                .build();
    }

    public static Query txnReceiptQueryFor(TransactionID txnId) {
        return txnReceiptQueryFor(txnId, false, false);
    }

    public static Query txnReceiptQueryFor(TransactionID txnId, boolean includeDuplicates, boolean getChildReceipts) {
        return Query.newBuilder()
                .setTransactionGetReceipt(TransactionGetReceiptQuery.newBuilder()
                        .setHeader(answerHeader(Transaction.getDefaultInstance()))
                        .setTransactionID(txnId)
                        .setIncludeDuplicates(includeDuplicates)
                        .setIncludeChildReceipts(getChildReceipts)
                        .build())
                .build();
    }

    public static long reflectForCost(Response response)
            throws InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        return (long) reflectForHeaderField(response, "cost");
    }

    public static ResponseCodeEnum reflectForPrecheck(Response response)
            throws InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        return (ResponseCodeEnum) reflectForHeaderField(response, "nodeTransactionPrecheckCode");
    }

    private static Object reflectForHeaderField(Response response, String field)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        final String getterName = Arrays.stream(Response.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(name -> !"hashCode".equals(name) && name.startsWith("has"))
                .filter(name -> {
                    try {
                        return (Boolean) Response.class.getMethod(name).invoke(response);
                    } catch (Exception ignored) {
                        // Intentionally ignored
                    }
                    return false;
                })
                .map(name -> name.replace("has", "get"))
                .findAny()
                .orElseThrow();

        Method getter = Response.class.getMethod(getterName);
        Class<?> getterClass = getter.getReturnType();
        Method headerMethod = getterClass.getMethod("getHeader");
        ResponseHeader header = (ResponseHeader) headerMethod.invoke(getter.invoke(response));
        Method fieldGetter = ResponseHeader.class.getMethod(asGetter(field));
        return fieldGetter.invoke(header);
    }

    public static String asGetter(String field) {
        return "get" + field.substring(0, 1).toUpperCase() + field.substring(1);
    }

    public static String lookUpAccountWithAlias(HapiSpec spec, String aliasKey) {
        final var lookedUpKey = spec.registry().getKey(aliasKey).toByteString().toStringUtf8();
        return asAccountString(spec.registry().getAccountID(lookedUpKey));
    }
}

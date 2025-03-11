// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.pbjToProto;
import static com.hedera.services.bdd.spec.dsl.entities.SpecContract.VARIANT_NONE;
import static com.hedera.services.bdd.spec.queries.contract.HapiContractCallLocal.fromDetails;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.consensus.HapiGetTopicInfo;
import com.hedera.services.bdd.spec.queries.contract.HapiContractCallLocal;
import com.hedera.services.bdd.spec.queries.contract.HapiGetContractBytecode;
import com.hedera.services.bdd.spec.queries.contract.HapiGetContractInfo;
import com.hedera.services.bdd.spec.queries.contract.HapiGetContractRecords;
import com.hedera.services.bdd.spec.queries.crypto.HapiGetAccountBalance;
import com.hedera.services.bdd.spec.queries.crypto.HapiGetAccountDetails;
import com.hedera.services.bdd.spec.queries.crypto.HapiGetAccountInfo;
import com.hedera.services.bdd.spec.queries.crypto.HapiGetAccountRecords;
import com.hedera.services.bdd.spec.queries.crypto.ReferenceType;
import com.hedera.services.bdd.spec.queries.file.HapiGetFileContents;
import com.hedera.services.bdd.spec.queries.file.HapiGetFileInfo;
import com.hedera.services.bdd.spec.queries.meta.HapiGetReceipt;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.spec.queries.meta.HapiGetVersionInfo;
import com.hedera.services.bdd.spec.queries.schedule.HapiGetScheduleInfo;
import com.hedera.services.bdd.spec.queries.token.HapiGetTokenInfo;
import com.hedera.services.bdd.spec.queries.token.HapiGetTokenNftInfo;
import com.hederahashgraph.api.proto.java.TransactionID;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public class QueryVerbs {
    public static HapiGetReceipt getReceipt(final String txn) {
        return new HapiGetReceipt(txn);
    }

    public static HapiGetReceipt getReceipt(final TransactionID txnId) {
        return new HapiGetReceipt(txnId);
    }

    public static HapiGetFileInfo getFileInfo(final String file) {
        return new HapiGetFileInfo(file);
    }

    public static HapiGetFileInfo getFileInfo(final Supplier<String> supplier) {
        return new HapiGetFileInfo(supplier);
    }

    public static HapiGetFileContents getFileContents(final String file) {
        return new HapiGetFileContents(file);
    }

    public static HapiGetAccountInfo getAccountInfo(final String account) {
        return new HapiGetAccountInfo(account);
    }

    public static HapiGetAccountInfo getAliasedAccountInfo(final String sourceKey) {
        return new HapiGetAccountInfo(sourceKey, ReferenceType.ALIAS_KEY_NAME);
    }

    public static HapiGetAccountInfo getAliasedAccountInfo(final ByteString evmAlias) {
        return new HapiGetAccountInfo(evmAlias, ReferenceType.LITERAL_ACCOUNT_ALIAS);
    }

    public static HapiGetAccountInfo getLiteralAliasAccountInfo(final String alias) {
        return new HapiGetAccountInfo(alias, ReferenceType.HEXED_CONTRACT_ALIAS);
    }

    public static HapiGetAccountRecords getAccountRecords(final String account) {
        return new HapiGetAccountRecords(account);
    }

    public static HapiGetAccountDetails getAccountDetails(final String account) {
        return new HapiGetAccountDetails(account).nodePayment(1234L);
    }

    public static HapiGetAccountDetails getAccountDetailsNoPayment(final String account) {
        return new HapiGetAccountDetails(account).nodePayment(1234L);
    }

    public static HapiGetTxnRecord getTxnRecord(final String txn) {
        return new HapiGetTxnRecord(txn);
    }

    public static HapiGetTxnRecord getTxnRecord(final TransactionID txnId) {
        return new HapiGetTxnRecord(txnId);
    }

    public static HapiGetTxnRecord getTxnRecord(final com.hedera.hapi.node.base.TransactionID txnId) {
        return new HapiGetTxnRecord(
                pbjToProto(txnId, com.hedera.hapi.node.base.TransactionID.class, TransactionID.class));
    }

    public static HapiGetContractInfo getContractInfo(final String contract) {
        return new HapiGetContractInfo(contract);
    }

    public static HapiGetContractInfo getContractInfo(final String contract, final boolean idPredefined) {
        return new HapiGetContractInfo(contract, idPredefined);
    }

    public static HapiGetContractInfo getLiteralAliasContractInfo(final String evmAddress) {
        return new HapiGetContractInfo(evmAddress);
    }

    public static HapiGetContractBytecode getContractBytecode(final String contract) {
        return new HapiGetContractBytecode(contract);
    }

    public static HapiGetContractRecords getContractRecords(final String contract) {
        return new HapiGetContractRecords(contract);
    }

    /**
     * This method allows the developer to invoke a contract function by the name of the called
     * contract and the name of the desired function
     *
     * @param contract the name of the contract
     * @param functionName the name of the function
     * @param params the arguments (if any) passed to the contract's function
     */
    public static HapiContractCallLocal contractCallLocal(
            final String contract, final String functionName, final Object... params) {
        return contractCallLocal(Optional.empty(), contract, functionName, params);
    }

    /**
     * This method allows the developer to invoke a contract function by the name of the called
     * contract and the name of the desired function.  This version allows for a variant contract root folder
     *
     * @param variant variant contract root folder
     * @param contract the name of the contract
     * @param functionName the name of the function
     * @param params the arguments (if any) passed to the contract's function
     */
    public static HapiContractCallLocal contractCallLocal(
            final Optional<String> variant, final String contract, final String functionName, final Object... params) {
        final var abi = getABIFor(variant.orElse(VARIANT_NONE), FUNCTION, functionName, contract);
        return new HapiContractCallLocal(abi, contract, params);
    }

    public static HapiContractCallLocal explicitContractCallLocal(final String contract, final byte[] encodedParams) {
        return new HapiContractCallLocal(contract, encodedParams);
    }

    /**
     * This method provides for the proper execution of specs, which execute contract local calls
     * with a function ABI instead of function name
     *
     * @param contract the name of the contract
     * @param abi the contract's function ABI
     * @param params the arguments (if any) passed to the contract's function
     */
    public static HapiContractCallLocal contractCallLocalWithFunctionAbi(
            final String contract, final String abi, final Object... params) {
        return new HapiContractCallLocal(abi, contract, params);
    }

    public static HapiContractCallLocal contractCallLocalFrom(final String details) {
        return fromDetails(details);
    }

    public static HapiContractCallLocal contractCallLocal(
            final String contract, final String functionName, final Function<HapiSpec, Object[]> fn) {
        final var abi = getABIFor(FUNCTION, functionName, contract);
        return new HapiContractCallLocal(abi, contract, fn);
    }

    public static HapiGetAccountBalance getAccountBalance(final String account) {
        return new HapiGetAccountBalance(account);
    }

    public static HapiGetAccountBalance getAccountBalance(final String account, final boolean isContract) {
        return new HapiGetAccountBalance(account, isContract);
    }

    public static HapiGetAccountBalance getAutoCreatedAccountBalance(final String sourceKey) {
        return new HapiGetAccountBalance(sourceKey, ReferenceType.ALIAS_KEY_NAME);
    }

    public static HapiGetAccountBalance getAliasedContractBalance(final String hexedAlias) {
        return new HapiGetAccountBalance(hexedAlias, ReferenceType.HEXED_CONTRACT_ALIAS);
    }

    public static HapiGetAccountBalance getAliasedAccountBalance(final ByteString alias) {
        return new HapiGetAccountBalance(alias, ReferenceType.LITERAL_ACCOUNT_ALIAS);
    }

    public static HapiGetAccountBalance getAccountBalance(final Supplier<String> supplier) {
        return new HapiGetAccountBalance(supplier);
    }

    public static HapiGetTopicInfo getTopicInfo(final String topic) {
        return new HapiGetTopicInfo(topic);
    }

    public static HapiGetVersionInfo getVersionInfo() {
        return new HapiGetVersionInfo();
    }

    public static HapiGetTokenInfo getTokenInfo(final String token) {
        return new HapiGetTokenInfo(token);
    }

    public static HapiGetScheduleInfo getScheduleInfo(final String schedule) {
        return new HapiGetScheduleInfo(schedule);
    }

    public static HapiGetTokenNftInfo getTokenNftInfo(final String token, final long serialNum) {
        return new HapiGetTokenNftInfo(token, serialNum);
    }
}

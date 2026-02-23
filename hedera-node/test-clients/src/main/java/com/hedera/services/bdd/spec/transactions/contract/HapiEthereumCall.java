// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.contract;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.extractTxnId;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.getEcdsaPrivateKeyFromSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateLargeFile;
import static com.hedera.services.bdd.suites.HapiSuite.CHAIN_ID;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_CONTRACT_SENDER;
import static com.hedera.services.bdd.suites.HapiSuite.ETH_HASH_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.FIVE_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.MAX_CALL_DATA_SIZE;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.WEIBARS_IN_A_TINYBAR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.esaulpaugh.headlong.util.Integers;
import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.infrastructure.meta.ActionableContractCall;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.file.HapiFileCreate;
import com.hedera.services.bdd.suites.contract.Utils;
import com.hedera.services.bdd.utils.Signing;
import com.hederahashgraph.api.proto.java.EthereumTransactionBody;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.ObjLongConsumer;
import java.util.function.Supplier;
import org.bouncycastle.util.encoders.Hex;

public class HapiEthereumCall extends HapiBaseCall<HapiEthereumCall> {
    private static final String CALL_DATA_FILE_NAME = "CallData";
    private List<String> otherSigs = Collections.emptyList();
    private Optional<String> details = Optional.empty();
    private Optional<Function<HapiSpec, Object[]>> paramsFn = Optional.empty();
    private Optional<ObjLongConsumer<ResponseCodeEnum>> gasObserver = Optional.empty();

    public static final long DEFAULT_GAS_PRICE_TINYBARS = 50L;
    private EthTxData.EthTransactionType type = EthTxData.EthTransactionType.EIP1559;
    private long nonce = 0L;
    private boolean useSpecNonce = true;
    private BigInteger gasPrice = WEIBARS_IN_A_TINYBAR.multiply(BigInteger.valueOf(DEFAULT_GAS_PRICE_TINYBARS));
    private BigInteger maxFeePerGas = WEIBARS_IN_A_TINYBAR.multiply(BigInteger.valueOf(DEFAULT_GAS_PRICE_TINYBARS));
    private long maxPriorityGas = 1_000L;
    private Optional<Long> maxGasAllowance = Optional.of(FIVE_HBARS);
    private Optional<BigInteger> valueSent = Optional.of(BigInteger.ZERO); // weibar
    private Consumer<Object[]> resultObserver = null;
    private Consumer<ByteString> eventDataObserver = null;
    private Optional<FileID> ethFileID = Optional.empty();
    private boolean createCallDataFile;
    private boolean isTokenFlow;
    private String account = null;
    private ByteString alias = null;
    private byte[] explicitTo = null;
    private Integer chainId = CHAIN_ID;
    private boolean wrongRecId;
    private boolean isJumboTxn = false;

    public HapiEthereumCall withExplicitParams(final Supplier<String> supplier) {
        explicitHexedParams = Optional.of(supplier);
        return this;
    }

    public static HapiEthereumCall fromDetails(String actionable) {
        HapiEthereumCall call = new HapiEthereumCall();
        call.details = Optional.of(actionable);
        return call;
    }

    public HapiEthereumCall withWrongParityRecId() {
        wrongRecId = true;
        return this;
    }

    private HapiEthereumCall() {}

    public HapiEthereumCall(String contract) {
        this.abi = Optional.of(FALLBACK_ABI);
        this.params = Optional.of(new Object[0]);
        this.contract = contract;
        this.payer = Optional.of(RELAYER);
    }

    public HapiEthereumCall(String account, long amount) {
        this.account = account;
        this.valueSent = Optional.of(WEIBARS_IN_A_TINYBAR.multiply(BigInteger.valueOf(amount)));
        this.abi = Optional.of(FALLBACK_ABI);
        this.params = Optional.of(new Object[0]);
        this.payer = Optional.of(RELAYER);
    }

    public HapiEthereumCall(ByteString account, long amount) {
        this.alias = account;
        this.valueSent = Optional.of(WEIBARS_IN_A_TINYBAR.multiply(BigInteger.valueOf(amount)));
        this.abi = Optional.of(FALLBACK_ABI);
        this.params = Optional.of(new Object[0]);
        this.payer = Optional.of(RELAYER);
    }

    public static HapiEthereumCall explicitlyTo(@NonNull final byte[] to, long amount) {
        final var call = new HapiEthereumCall();
        call.explicitTo = to;
        call.valueSent = Optional.of(WEIBARS_IN_A_TINYBAR.multiply(BigInteger.valueOf(amount)));
        call.abi = Optional.of(FALLBACK_ABI);
        call.params = Optional.of(new Object[0]);
        call.payer = Optional.of(RELAYER);
        return call;
    }

    public HapiEthereumCall(final HapiContractCall contractCall) {
        this.abi = Optional.of(contractCall.getAbi());
        this.params = Optional.of(contractCall.getParams());
        this.contract = contractCall.getContract();
        this.txnName = contractCall.getTxnName();
        this.gas = contractCall.getGas();
        this.expectedStatus = Optional.of(contractCall.getExpectedStatus());
        this.payer = contractCall.getPayer();
        this.expectedPrecheck = Optional.of(contractCall.getExpectedPrecheck());
        this.fiddler = contractCall.getFiddler();
        this.memo = contractCall.getMemo();
        this.fee = contractCall.getFee();
        this.validDurationSecs = contractCall.getValidDurationSeconds();
        this.customTxnId = contractCall.getCustomTxnId();
        this.node = contractCall.getNode();
        this.usdFee = contractCall.getUsdFee();
        this.retryLimits = contractCall.getRetryLimits();
        this.resultObserver = contractCall.getResultObserver();
        this.explicitHexedParams = contractCall.getExplicitHexedParams();
        this.privateKeyRef = contractCall.getPrivateKeyRef();
        this.deferStatusResolution = contractCall.getDeferStatusResolution();
        if (contractCall.getValueSent().isPresent()) {
            this.valueSent = Optional.of(WEIBARS_IN_A_TINYBAR.multiply(
                    BigInteger.valueOf(contractCall.getValueSent().orElseThrow())));
        }
        if (!contractCall.otherSigs.isEmpty()) {
            this.alsoSigningWithFullPrefix(contractCall.otherSigs.toArray(new String[0]));
        }
        shouldRegisterTxn = true;
        this.permissibleStatuses = contractCall.getPermissibleStatuses();
        this.permissiblePrechecks = contractCall.getPermissiblePrechecks();
    }

    public HapiEthereumCall notTryingAsHexedliteral() {
        tryAsHexedAddressIfLenMatches = false;
        return this;
    }

    public HapiEthereumCall(String abi, String contract, Object... params) {
        this.abi = Optional.of(abi);
        this.params = Optional.of(params);
        this.contract = contract;
    }

    public HapiEthereumCall(boolean isTokenFlow, String abi, String contract, Object... params) {
        this.abi = Optional.of(abi);
        this.params = Optional.of(params);
        this.contract = contract;
        this.isTokenFlow = isTokenFlow;
    }

    public HapiEthereumCall(String abi, String contract, Function<HapiSpec, Object[]> fn) {
        this(abi, contract);
        paramsFn = Optional.of(fn);
    }

    public HapiEthereumCall exposingResultTo(final Consumer<Object[]> observer) {
        resultObserver = observer;
        return this;
    }

    public HapiEthereumCall exposingEventDataTo(final Consumer<ByteString> observer) {
        eventDataObserver = observer;
        return this;
    }

    public HapiEthereumCall exposingGasTo(ObjLongConsumer<ResponseCodeEnum> gasObserver) {
        this.gasObserver = Optional.of(gasObserver);
        return this;
    }

    public HapiEthereumCall alsoSigningWithFullPrefix(String... keys) {
        otherSigs = List.of(keys);
        return sigMapPrefixes(uniqueWithFullPrefixesFor(keys));
    }

    public HapiEthereumCall sending(long amountInTinybars) {
        valueSent = Optional.of(WEIBARS_IN_A_TINYBAR.multiply(BigInteger.valueOf(amountInTinybars)));
        return this;
    }

    public HapiEthereumCall sendingWeibars(final BigInteger amountInWeibars) {
        valueSent = Optional.of(amountInWeibars);
        return this;
    }

    public HapiEthereumCall maxGasAllowance(long maxGasAllowance) {
        this.maxGasAllowance = Optional.of(maxGasAllowance);
        return this;
    }

    public HapiEthereumCall signingWith(String signingWith) {
        this.privateKeyRef = signingWith;
        return this;
    }

    public HapiEthereumCall type(EthTxData.EthTransactionType type) {
        this.type = type;
        return this;
    }

    public HapiEthereumCall nonce(long nonce) {
        this.nonce = nonce;
        useSpecNonce = false;
        return this;
    }

    public HapiEthereumCall chainId(@Nullable Integer chainId) {
        this.chainId = chainId;
        return this;
    }

    public HapiEthereumCall gasPrice(long gasPrice) {
        this.gasPrice = WEIBARS_IN_A_TINYBAR.multiply(BigInteger.valueOf(gasPrice));
        return this;
    }

    public HapiEthereumCall maxFeePerGas(long maxFeePerGas) {
        this.maxFeePerGas = WEIBARS_IN_A_TINYBAR.multiply(BigInteger.valueOf(maxFeePerGas));
        return this;
    }

    public HapiEthereumCall maxPriorityGas(long maxPriorityGas) {
        this.maxPriorityGas = maxPriorityGas;
        return this;
    }

    public HapiEthereumCall gasLimit(long gasLimit) {
        this.gas = Optional.of(gasLimit);
        return this;
    }

    public HapiEthereumCall createCallDataFile() {
        this.createCallDataFile = true;
        return this;
    }

    @Override
    protected HapiEthereumCall self() {
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.EthereumTransaction;
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
        return spec.fees()
                .forActivityBasedOp(
                        HederaFunctionality.EthereumTransaction,
                        scFees::getEthereumTransactionFeeMatrices,
                        txn,
                        numPayerKeys);
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(HapiSpec spec) throws Throwable {
        if (details.isPresent()) {
            ActionableContractCall actionable = spec.registry().getActionableCall(details.get());
            contract = actionable.getContract();
            abi = Optional.of(actionable.getDetails().getAbi());
            params = Optional.of(actionable.getDetails().getExampleArgs());
        } else {
            paramsFn.ifPresent(hapiApiSpecFunction -> params = Optional.of(hapiApiSpecFunction.apply(spec)));
        }

        byte[] callData = initializeCallData();

        final byte[] to;
        if (explicitTo != null) {
            to = explicitTo;
        } else if (alias != null) {
            to = recoverAddressFromPubKey(alias.toByteArray());
        } else if (account != null) {
            to = Utils.asAddress(spec.registry().getAccountID(account));
        } else if (isTokenFlow) {
            to = Utils.asAddress(spec.registry().getTokenID(contract));
        } else {
            if (!tryAsHexedAddressIfLenMatches) {
                to = Utils.asAddress(spec.registry().getContractId(contract));
            } else {
                to = Utils.asAddress(TxnUtils.asContractId(contract, spec));
            }
        }

        final var gasPriceBytes = gasLongToBytes(gasPrice.longValueExact());

        final var maxFeePerGasBytes = gasLongToBytes(maxFeePerGas.longValueExact());
        final var maxPriorityGasBytes = gasLongToBytes(maxPriorityGas);

        if (useSpecNonce) {
            nonce = spec.getNonce(privateKeyRef);
        }
        final var ethTxData = new EthTxData(
                null,
                type,
                Integers.toBytes(chainId),
                nonce,
                gasPriceBytes,
                maxPriorityGasBytes,
                maxFeePerGasBytes,
                gas.orElse(100_000L),
                to,
                valueSent.orElse(BigInteger.ZERO),
                callData,
                new byte[] {},
                null,
                0,
                null,
                null,
                null);

        byte[] privateKeyByteArray = getEcdsaPrivateKeyFromSpec(spec, privateKeyRef);
        var signedEthTxData = Signing.signMessage(ethTxData, privateKeyByteArray, wrongRecId);
        spec.registry().saveBytes(ETH_HASH_KEY, ByteString.copyFrom((signedEthTxData.getEthereumHash())));

        if (createCallDataFile || (!isJumboTxn && callData.length > MAX_CALL_DATA_SIZE)) {
            final var callDataBytesString = ByteString.copyFrom(Hex.encode(callData));
            final var createFile = new HapiFileCreate(CALL_DATA_FILE_NAME);
            final var updateLargeFile =
                    updateLargeFile(payer.orElse(DEFAULT_CONTRACT_SENDER), CALL_DATA_FILE_NAME, callDataBytesString);
            createFile.execFor(spec);
            updateLargeFile.execFor(spec);
            ethFileID = Optional.of(TxnUtils.asFileId(CALL_DATA_FILE_NAME, spec));
            signedEthTxData = signedEthTxData.replaceCallData(new byte[] {});
        }
        final var finalEthTxData = signedEthTxData;

        final EthereumTransactionBody ethOpBody = spec.txns()
                .<EthereumTransactionBody, EthereumTransactionBody.Builder>body(
                        EthereumTransactionBody.class, builder -> {
                            builder.setEthereumData(ByteString.copyFrom(finalEthTxData.encodeTx()));
                            maxGasAllowance.ifPresent(builder::setMaxGasAllowance);
                            ethFileID.ifPresent(builder::setCallData);
                        });
        return b -> b.setEthereumTransaction(ethOpBody);
    }

    @Override
    protected void updateStateOf(final HapiSpec spec) throws Throwable {
        if (actualPrecheck == OK) {
            spec.incrementNonce(privateKeyRef);
        }
        if (gasObserver.isPresent()) {
            doGasLookup(gas -> gasObserver.get().accept(actualStatus, gas), spec, txnSubmitted, false);
        }
        if (resultObserver != null) {
            doObservedLookup(spec, txnSubmitted, rcd -> {
                final var function = com.esaulpaugh.headlong.abi.Function.fromJson(abi.orElse(null));
                final var result = function.decodeReturn(
                        rcd.getContractCallResult().getContractCallResult().toByteArray());
                resultObserver.accept(result.toArray());
            });
        }
        if (eventDataObserver != null) {
            doObservedLookup(
                    spec,
                    txnSubmitted,
                    rcd -> eventDataObserver.accept(
                            rcd.getContractCallResult().getLogInfo(0).getData()));
        }
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        final var signers = new ArrayList<Function<HapiSpec, Key>>();
        signers.add(spec -> spec.registry().getKey(effectivePayer(spec)));
        for (final var added : otherSigs) {
            signers.add(spec -> spec.registry().getKey(added));
        }
        return signers;
    }

    static void doGasLookup(
            final LongConsumer gasObserver, final HapiSpec spec, final Transaction txn, final boolean isCreate)
            throws Throwable {
        doObservedLookup(spec, txn, rcd -> {
            final var gasUsed = isCreate
                    ? rcd.getContractCreateResult().getGasUsed()
                    : rcd.getContractCallResult().getGasUsed();
            gasObserver.accept(gasUsed);
        });
    }

    static void doObservedLookup(final HapiSpec spec, final Transaction txn, Consumer<TransactionRecord> observer)
            throws Throwable {
        final var txnId = extractTxnId(txn);
        final var lookup = getTxnRecord(txnId)
                .assertingNothing()
                .noLogging()
                .payingWith(GENESIS)
                .nodePayment(1)
                .exposingTo(observer);
        allRunFor(spec, lookup);
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper()
                .add("contract", contract)
                .add("abi", abi)
                .add("params", Arrays.toString(params.orElse(null)));
    }

    public HapiEthereumCall markAsJumboTxn() {
        isJumboTxn = true;
        return this;
    }
}

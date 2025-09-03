// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.contract;

import static com.hedera.services.bdd.spec.utilops.UtilVerbs.getEcdsaPrivateKeyFromSpec;
import static com.hedera.services.bdd.suites.HapiSuite.CHAIN_ID;
import static com.hedera.services.bdd.suites.HapiSuite.ETH_HASH_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.ETH_SENDER_ADDRESS;
import static com.hedera.services.bdd.suites.HapiSuite.FIVE_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.MAX_CALL_DATA_SIZE;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.WEIBARS_IN_A_TINYBAR;

import com.esaulpaugh.headlong.util.Integers;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.node.app.hapi.utils.ethereum.EthTxSigs;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.suites.contract.Utils;
import com.hedera.services.bdd.utils.Signing;
import com.hederahashgraph.api.proto.java.EthereumTransactionBody;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import org.apache.tuweni.bytes.Bytes;

public class HapiEthereumContractCreate extends HapiBaseContractCreate<HapiEthereumContractCreate> {
    private EthTxData.EthTransactionType type;
    private long nonce = 0L;
    private boolean useSpecNonce = true;
    private BigInteger gasPrice = WEIBARS_IN_A_TINYBAR.multiply(BigInteger.valueOf(50L));
    private BigInteger maxFeePerGas = WEIBARS_IN_A_TINYBAR.multiply(BigInteger.valueOf(50L));
    private long maxPriorityGas = 20_000L;
    private Optional<FileID> ethFileID = Optional.empty();
    private boolean invalidateEthData = false;
    private Long maxGasAllowance = ONE_HUNDRED_HBARS;
    private String privateKeyRef = SECP_256K1_SOURCE_KEY;
    private Integer chainId = CHAIN_ID;
    private boolean isJumboTxn = false;

    @Nullable
    private BiConsumer<HapiSpec, EthereumTransactionBody.Builder> spec;

    public HapiEthereumContractCreate exposingNumTo(LongConsumer obs) {
        newNumObserver = Optional.of(obs);
        return this;
    }

    public HapiEthereumContractCreate proxy(String proxy) {
        this.proxy = Optional.of(proxy);
        return this;
    }

    public HapiEthereumContractCreate advertisingCreation() {
        advertiseCreation = true;
        return this;
    }

    public HapiEthereumContractCreate chainId(Integer chainId) {
        this.chainId = chainId;
        return this;
    }

    public HapiEthereumContractCreate(String contract) {
        super(contract);
        this.payer = Optional.of(RELAYER);
        super.omitAdminKey = true;
    }

    public HapiEthereumContractCreate(
            HapiContractCreate contractCreate, String privateKeyRef, Key adminKey, long defaultGas) {
        super(contractCreate.contract);
        this.adminKey = adminKey;
        this.privateKeyRef = privateKeyRef;
        this.type = EthTransactionType.EIP1559;
        this.omitAdminKey = contractCreate.omitAdminKey;
        this.makeImmutable = contractCreate.makeImmutable;
        this.advertiseCreation = contractCreate.advertiseCreation;
        this.shouldAlsoRegisterAsAccount = contractCreate.shouldAlsoRegisterAsAccount;
        this.useDeprecatedAdminKey = contractCreate.useDeprecatedAdminKey;
        this.contract = contractCreate.contract;
        this.key = contractCreate.key;
        this.autoRenewPeriodSecs = contractCreate.autoRenewPeriodSecs;
        this.balance = contractCreate.balance;
        this.adminKeyControl = contractCreate.adminKeyControl;
        this.adminKeyType = contractCreate.adminKeyType;
        this.memo = contractCreate.memo;
        this.bytecodeFile = contractCreate.bytecodeFile;
        this.bytecodeFileFn = contractCreate.bytecodeFileFn;
        this.successCb = contractCreate.successCb;
        this.abi = contractCreate.abi;
        this.args = contractCreate.args;
        this.gasObserver = contractCreate.gasObserver;
        this.newNumObserver = contractCreate.newNumObserver;
        this.proxy = contractCreate.proxy;
        this.explicitHexedParams = contractCreate.explicitHexedParams;
        this.stakedAccountId = contractCreate.stakedAccountId;
        this.stakedNodeId = contractCreate.stakedNodeId;
        this.isDeclinedReward = contractCreate.isDeclinedReward;
        final var gas = contractCreate.gas.isPresent() ? contractCreate.gas.getAsLong() : defaultGas;
        this.gas(gas);
        this.shouldRegisterTxn = true;
        this.deferStatusResolution = contractCreate.getDeferStatusResolution();
        this.txnName = contractCreate.getTxnName();
        this.expectedStatus = Optional.of(contractCreate.getExpectedStatus());
        this.expectedPrecheck = Optional.of(contractCreate.getExpectedPrecheck());
        this.fiddler = contractCreate.getFiddler();
        this.validDurationSecs = contractCreate.getValidDurationSecs();
        this.customTxnId = contractCreate.getCustomTxnId();
        this.node = contractCreate.getNode();
        this.usdFee = contractCreate.getUsdFee();
        this.retryLimits = contractCreate.getRetryLimits();
        this.permissibleStatuses = contractCreate.getPermissibleStatuses();
        this.permissiblePrechecks = contractCreate.getPermissiblePrechecks();
        this.payer = contractCreate.getPayer();
        this.fee = contractCreate.getFee();
        this.maxGasAllowance = FIVE_HBARS;
    }

    public HapiEthereumContractCreate(
            @NonNull final String contract, @NonNull final BiConsumer<HapiSpec, EthereumTransactionBody.Builder> spec) {
        super(contract);
        this.spec = spec;
        this.payer = Optional.of(RELAYER);
        super.omitAdminKey = true;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.EthereumTransaction;
    }

    @Override
    protected HapiEthereumContractCreate self() {
        return this;
    }

    @Override
    protected Key lookupKey(HapiSpec spec, String name) {
        return spec.registry().getKey(name);
    }

    public HapiEthereumContractCreate bytecode(String fileName) {
        bytecodeFile = Optional.of(fileName);
        return this;
    }

    public HapiEthereumContractCreate invalidateEthereumData() {
        invalidateEthData = true;
        return this;
    }

    public HapiEthereumContractCreate bytecode(Supplier<String> supplier) {
        bytecodeFileFn = Optional.of(supplier);
        return this;
    }

    public HapiEthereumContractCreate autoRenewSecs(long period) {
        autoRenewPeriodSecs = Optional.of(period);
        return this;
    }

    public HapiEthereumContractCreate balance(long initial) {
        balance = Optional.of(initial);
        return this;
    }

    public HapiEthereumContractCreate gas(long amount) {
        gas = OptionalLong.of(amount);
        return this;
    }

    public HapiEthereumContractCreate entityMemo(String s) {
        memo = Optional.of(s);
        return this;
    }

    public HapiEthereumContractCreate maxGasAllowance(long maxGasAllowance) {
        this.maxGasAllowance = maxGasAllowance;
        return this;
    }

    public HapiEthereumContractCreate signingWith(String signingWith) {
        this.privateKeyRef = signingWith;
        return this;
    }

    public HapiEthereumContractCreate type(EthTxData.EthTransactionType type) {
        this.type = type;
        return this;
    }

    public HapiEthereumContractCreate nonce(long nonce) {
        this.nonce = nonce;
        useSpecNonce = false;
        return this;
    }

    public HapiEthereumContractCreate gasPrice(long gasPrice) {
        this.gasPrice = WEIBARS_IN_A_TINYBAR.multiply(BigInteger.valueOf(gasPrice));
        return this;
    }

    public HapiEthereumContractCreate maxFeePerGas(long maxFeePerGas) {
        this.maxFeePerGas = WEIBARS_IN_A_TINYBAR.multiply(BigInteger.valueOf(maxFeePerGas));
        return this;
    }

    public HapiEthereumContractCreate maxPriorityGas(long maxPriorityGas) {
        this.maxPriorityGas = maxPriorityGas;
        return this;
    }

    public HapiEthereumContractCreate gasLimit(long gasLimit) {
        this.gas = OptionalLong.of(gasLimit);
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(HapiSpec spec) throws Throwable {
        if (this.spec != null) {
            return b -> {
                try {
                    b.setEthereumTransaction(explicitEthereumTransaction(spec));
                } catch (InvocationTargetException | NoSuchMethodException | IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            };
        }
        bytecodeFileFn.ifPresent(stringSupplier -> bytecodeFile = Optional.of(stringSupplier.get()));
        if (bytecodeFile.isEmpty()) {
            setBytecodeToDefaultContract(spec);
        }

        final var filePath = Utils.getResourcePath(bytecodeFile.get(), ".bin");
        final var fileContents = Utils.extractByteCode(filePath);

        ByteString bytecode = fileContents;
        if (args.isPresent() && abi.isPresent()) {
            bytecode = bytecode.concat(TxnUtils.constructorArgsToByteString(abi.get(), args.get()));
        }
        final var callData =
                Bytes.fromHexString(new String(bytecode.toByteArray())).toArray();

        final var gasPriceBytes = gasLongToBytes(gasPrice.longValueExact());

        final var maxFeePerGasBytes = gasLongToBytes(maxFeePerGas.longValueExact());
        final var maxPriorityGasBytes = gasLongToBytes(maxPriorityGas);

        if (useSpecNonce) {
            nonce = spec.getNonce(privateKeyRef);
            spec.incrementNonce(privateKeyRef);
        }
        final var ethTxData = new EthTxData(
                null,
                type,
                Integers.toBytes(chainId),
                nonce,
                gasPriceBytes,
                maxPriorityGasBytes,
                maxFeePerGasBytes,
                gas.orElse(0L),
                new byte[] {},
                weibarsToTinybars(balance).orElse(BigInteger.ZERO),
                callData,
                new byte[] {},
                null,
                0,
                null,
                null,
                null);

        final byte[] privateKeyByteArray = getEcdsaPrivateKeyFromSpec(spec, privateKeyRef);
        var signedEthTxData = Signing.signMessage(ethTxData, privateKeyByteArray);
        spec.registry().saveBytes(ETH_HASH_KEY, ByteString.copyFrom((signedEthTxData.getEthereumHash())));

        final var extractedSignatures = EthTxSigs.extractSignatures(signedEthTxData);
        final var senderAddress = ByteString.copyFrom(extractedSignatures.address());
        spec.registry().saveBytes(ETH_SENDER_ADDRESS, senderAddress);

        if (!isJumboTxn && fileContents.toByteArray().length > MAX_CALL_DATA_SIZE) {
            ethFileID = Optional.of(TxnUtils.asFileId(bytecodeFile.get(), spec));
            signedEthTxData = signedEthTxData.replaceCallData(new byte[] {});
        }

        final var ethData = signedEthTxData;
        final EthereumTransactionBody opBody = spec.txns()
                .<EthereumTransactionBody, EthereumTransactionBody.Builder>body(
                        EthereumTransactionBody.class, builder -> {
                            if (invalidateEthData) {
                                builder.setEthereumData(ByteString.EMPTY);
                            } else {
                                builder.setEthereumData(ByteString.copyFrom(ethData.encodeTx()));
                            }
                            ethFileID.ifPresent(builder::setCallData);
                            builder.setMaxGasAllowance(maxGasAllowance);
                        });

        return b -> {
            this.fee.ifPresent(b::setTransactionFee);
            this.memo.ifPresent(b::setMemo);
            b.setEthereumTransaction(opBody);
        };
    }

    private Optional<BigInteger> weibarsToTinybars(Optional<Long> balance) {
        if (balance.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(WEIBARS_IN_A_TINYBAR.multiply(BigInteger.valueOf(balance.get())));
        } catch (ArithmeticException e) {
            return Optional.empty();
        }
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerSigs) throws Throwable {
        return spec.fees()
                .forActivityBasedOp(
                        HederaFunctionality.EthereumTransaction,
                        scFees::getEthereumTransactionFeeMatrices,
                        txn,
                        numPayerSigs);
    }

    private EthereumTransactionBody explicitEthereumTransaction(HapiSpec spec)
            throws InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        return spec.txns()
                .<EthereumTransactionBody, EthereumTransactionBody.Builder>body(
                        EthereumTransactionBody.class,
                        b -> Objects.requireNonNull(this.spec).accept(spec, b));
    }

    public HapiEthereumContractCreate markAsJumboTxn() {
        isJumboTxn = true;
        return this;
    }
}

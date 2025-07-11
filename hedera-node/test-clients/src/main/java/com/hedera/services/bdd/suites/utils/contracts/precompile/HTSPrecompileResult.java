// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.contracts.precompile;

import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.ADDRESS;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.BYTES32;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.EXPIRY;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FIXED_FEE;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FRACTIONAL_FEE;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.HEDERA_TOKEN_V3;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.HEDERA_TOKEN_WITH_METADATA;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.KEY_VALUE;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.RESPONSE_STATUS_AT_BEGINNING;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.ROYALTY_FEE;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.addressTuple;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.bigIntegerTuple;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.booleanTuple;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.burnReturnType;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.decimalsType;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.hapiAllowanceOfType;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.hapiGetApprovedType;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.intBoolTuple;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.intPairTuple;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.mintReturnType;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.notSpecifiedType;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.stringTuple;

import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FunctionType;
import com.hedera.services.bdd.suites.contract.Utils;
import com.hedera.services.bdd.suites.utils.contracts.ContractCallResult;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CustomFee;
import com.hederahashgraph.api.proto.java.FixedFee;
import com.hederahashgraph.api.proto.java.FractionalFee;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.RoyaltyFee;
import com.hederahashgraph.api.proto.java.TokenInfo;
import com.hederahashgraph.api.proto.java.TokenNftInfo;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;

public class HTSPrecompileResult implements ContractCallResult {
    private HTSPrecompileResult() {}

    public static final String ADDRESS_TYPE = "address";
    public static final String BYTES_32_TYPE = "bytes32";
    public static final String FIXED_FEE_REPLACED_ADDRESS = FIXED_FEE.replace(ADDRESS_TYPE, BYTES_32_TYPE);
    public static final String FRACTIONAL_FEE_REPLACED_ADDRESS = FRACTIONAL_FEE.replace(ADDRESS_TYPE, BYTES_32_TYPE);
    public static final String ROYALTY_FEE_REPLACED_ADDRESS = ROYALTY_FEE.replace(ADDRESS_TYPE, BYTES_32_TYPE);
    public static final String EXPIRY_REPLACED_ADDRESS = EXPIRY.replace(ADDRESS_TYPE, BYTES_32_TYPE);
    public static final String TOKEN_INFO_REPLACED_ADDRESS = "("
            + HEDERA_TOKEN_V3.replace(removeBrackets(ADDRESS), removeBrackets(BYTES32))
            + ",int64,bool,bool,bool,"
            + FIXED_FEE_REPLACED_ADDRESS
            + "[],"
            + FRACTIONAL_FEE_REPLACED_ADDRESS
            + "[],"
            + ROYALTY_FEE_REPLACED_ADDRESS
            + "[],string"
            + ")";
    public static final String TOKEN_INFO_V2 = "("
            + HEDERA_TOKEN_WITH_METADATA.replace(removeBrackets(ADDRESS), removeBrackets(BYTES32))
            + ",int64,bool,bool,bool,"
            + FIXED_FEE_REPLACED_ADDRESS
            + "[],"
            + FRACTIONAL_FEE_REPLACED_ADDRESS
            + "[],"
            + ROYALTY_FEE_REPLACED_ADDRESS
            + "[],string"
            + ")";
    public static final String FUNGIBLE_TOKEN_INFO_REPLACED_ADDRESS =
            "(" + TOKEN_INFO_REPLACED_ADDRESS + ",int32" + ")";
    public static final String FUNGIBLE_TOKEN_INFO_V2 = "(" + TOKEN_INFO_V2 + ",int32" + ")";
    public static final String NON_FUNGIBLE_TOKEN_INFO_REPLACED_ADDRESS =
            "(" + TOKEN_INFO_REPLACED_ADDRESS + ",int64,bytes32,int64,bytes,bytes32" + ")";
    public static final String NON_FUNGIBLE_TOKEN_INFO_V2 =
            "(" + TOKEN_INFO_V2 + ",int64,bytes32,int64,bytes,bytes32" + ")";

    public static final String KEY_VALUE_REPLACED_ADDRESS = KEY_VALUE.replace(ADDRESS_TYPE, BYTES_32_TYPE);

    public static final TupleType getTokenInfoTypeReplacedAddress =
            TupleType.parse(RESPONSE_STATUS_AT_BEGINNING + TOKEN_INFO_REPLACED_ADDRESS + ")");
    public static final TupleType getTokenInfoV2 = TupleType.parse(RESPONSE_STATUS_AT_BEGINNING + TOKEN_INFO_V2 + ")");
    public static final TupleType getFungibleTokenInfoTypeReplacedAddress =
            TupleType.parse(RESPONSE_STATUS_AT_BEGINNING + FUNGIBLE_TOKEN_INFO_REPLACED_ADDRESS + ")");
    public static final TupleType getFungibleTokenInfoV2 =
            TupleType.parse(RESPONSE_STATUS_AT_BEGINNING + FUNGIBLE_TOKEN_INFO_V2 + ")");
    public static final TupleType getNonFungibleTokenInfoTypeReplacedAddress =
            TupleType.parse(RESPONSE_STATUS_AT_BEGINNING + NON_FUNGIBLE_TOKEN_INFO_REPLACED_ADDRESS + ")");
    public static final TupleType getNonFungibleTokenInfoV2 =
            TupleType.parse(RESPONSE_STATUS_AT_BEGINNING + NON_FUNGIBLE_TOKEN_INFO_V2 + ")");
    public static final TupleType tokenGetCustomFeesReplacedAddress = TupleType.parse(RESPONSE_STATUS_AT_BEGINNING
            + FIXED_FEE_REPLACED_ADDRESS
            + "[],"
            + FRACTIONAL_FEE_REPLACED_ADDRESS
            + "[],"
            + ROYALTY_FEE_REPLACED_ADDRESS
            + "[])");
    public static final TupleType getTokenExpiryInfoTypeReplacedAddress =
            TupleType.parse(RESPONSE_STATUS_AT_BEGINNING + EXPIRY_REPLACED_ADDRESS + ")");
    public static final TupleType getTokenKeyReplacedAddress =
            TupleType.parse(RESPONSE_STATUS_AT_BEGINNING + KEY_VALUE_REPLACED_ADDRESS + ")");

    public static HTSPrecompileResult htsPrecompileResult() {
        return new HTSPrecompileResult();
    }

    private FunctionType functionType = FunctionType.NOT_SPECIFIED;
    private TupleType tupleType = notSpecifiedType;
    private ResponseCodeEnum status;
    private long totalSupply;
    private long[] serialNumbers;
    private int decimals;
    private byte[] owner;
    private byte[] approved;
    private String name;
    private String symbol;
    private String metadata;
    private long balance;
    private long allowance;
    private boolean ercFungibleTransferStatus;
    private boolean isApprovedForAllStatus;
    private TokenInfo tokenInfo;
    private TokenNftInfo nonFungibleTokenInfo;
    private boolean isKyc;
    private boolean tokenDefaultFreezeStatus;
    private boolean tokenDefaultKycStatus;
    private boolean isFrozen;
    private List<CustomFee> customFees;
    private boolean isToken;
    private int tokenType;
    private Key key;
    private long expiry;
    private long autoRenewPeriod;
    private AccountID autoRenewAccount;

    public HTSPrecompileResult forFunction(final FunctionType functionType) {
        tupleType = switch (functionType) {
            case HAPI_MINT -> mintReturnType;
            case HAPI_BURN -> burnReturnType;
            case ERC_TOTAL_SUPPLY, ERC_ALLOWANCE, ERC_BALANCE -> bigIntegerTuple;
            case ERC_DECIMALS -> decimalsType;
            case ERC_OWNER, ERC_GET_APPROVED -> addressTuple;
            case ERC_NAME, ERC_TOKEN_URI, ERC_SYMBOL -> stringTuple;
            case ERC_TRANSFER, ERC_IS_APPROVED_FOR_ALL -> booleanTuple;
            case HAPI_GET_APPROVED -> hapiGetApprovedType;
            case HAPI_ALLOWANCE -> hapiAllowanceOfType;
            case HAPI_IS_APPROVED_FOR_ALL,
                    HAPI_IS_TOKEN,
                    HAPI_IS_FROZEN,
                    GET_TOKEN_DEFAULT_KYC_STATUS,
                    GET_TOKEN_DEFAULT_FREEZE_STATUS,
                    HAPI_IS_KYC -> intBoolTuple;
            case HAPI_GET_TOKEN_INFO -> getTokenInfoTypeReplacedAddress;
            case HAPI_GET_TOKEN_INFO_V2 -> getTokenInfoV2;
            case HAPI_GET_FUNGIBLE_TOKEN_INFO -> getFungibleTokenInfoTypeReplacedAddress;
            case HAPI_GET_FUNGIBLE_TOKEN_INFO_V2 -> getFungibleTokenInfoV2;
            case HAPI_GET_NON_FUNGIBLE_TOKEN_INFO -> getNonFungibleTokenInfoTypeReplacedAddress;
            case HAPI_GET_NON_FUNGIBLE_TOKEN_INFO_V2 -> getNonFungibleTokenInfoV2;
            case HAPI_GET_TOKEN_CUSTOM_FEES -> tokenGetCustomFeesReplacedAddress;
            case HAPI_GET_TOKEN_KEY -> getTokenKeyReplacedAddress;
            case HAPI_GET_TOKEN_TYPE -> intPairTuple;
            case HAPI_GET_TOKEN_EXPIRY_INFO -> getTokenExpiryInfoTypeReplacedAddress;
            default -> notSpecifiedType;
        };

        this.functionType = functionType;
        return this;
    }

    public HTSPrecompileResult withStatus(final ResponseCodeEnum status) {
        this.status = status;
        return this;
    }

    public HTSPrecompileResult withTotalSupply(final long totalSupply) {
        this.totalSupply = totalSupply;
        return this;
    }

    public HTSPrecompileResult withSerialNumbers(final long... serialNumbers) {
        this.serialNumbers = serialNumbers;
        return this;
    }

    public HTSPrecompileResult withDecimals(final int decimals) {
        this.decimals = decimals;
        return this;
    }

    public HTSPrecompileResult withBalance(final long balance) {
        this.balance = balance;
        return this;
    }

    public HTSPrecompileResult withOwner(final byte[] address) {
        owner = address;
        return this;
    }

    public HTSPrecompileResult withSpender(final byte[] spender) {
        approved = spender;
        return this;
    }

    public HTSPrecompileResult withApproved(final ResponseCodeEnum status, final byte[] approved) {
        this.status = status;
        this.approved = approved;
        return this;
    }

    public HTSPrecompileResult withName(final String name) {
        this.name = name;
        return this;
    }

    public HTSPrecompileResult withSymbol(final String symbol) {
        this.symbol = symbol;
        return this;
    }

    public HTSPrecompileResult withTokenUri(final String tokenUri) {
        metadata = tokenUri;
        return this;
    }

    public HTSPrecompileResult withErcFungibleTransferStatus(final boolean ercFungibleTransferStatus) {
        this.ercFungibleTransferStatus = ercFungibleTransferStatus;
        return this;
    }

    public HTSPrecompileResult withAllowance(final long allowance) {
        this.allowance = allowance;
        return this;
    }

    public HTSPrecompileResult withIsApprovedForAll(final boolean isApprovedForAllStatus) {
        this.isApprovedForAllStatus = isApprovedForAllStatus;
        return this;
    }

    public HTSPrecompileResult withIsApprovedForAll(
            final ResponseCodeEnum status, final boolean isApprovedForAllStatus) {
        this.status = status;
        this.isApprovedForAllStatus = isApprovedForAllStatus;
        return this;
    }

    public HTSPrecompileResult withTokenInfo(final TokenInfo tokenInfo) {
        this.tokenInfo = tokenInfo;
        return this;
    }

    public HTSPrecompileResult withNftTokenInfo(final TokenNftInfo nonFungibleTokenInfo) {
        this.nonFungibleTokenInfo = nonFungibleTokenInfo;
        return this;
    }

    public HTSPrecompileResult withIsKyc(final boolean isKyc) {
        this.isKyc = isKyc;
        return this;
    }

    public HTSPrecompileResult withCustomFees(final List<CustomFee> customFees) {
        this.customFees = customFees;
        return this;
    }

    public HTSPrecompileResult withExpiry(
            final long expiry, final AccountID autoRenewAccount, final long autoRenewPeriod) {
        this.expiry = expiry;
        this.autoRenewAccount = autoRenewAccount;
        this.autoRenewPeriod = autoRenewPeriod;
        return this;
    }

    public HTSPrecompileResult withTokenDefaultFreezeStatus(final boolean tokenDefaultFreezeStatus) {
        this.tokenDefaultFreezeStatus = tokenDefaultFreezeStatus;
        return this;
    }

    public HTSPrecompileResult withTokenDefaultKycStatus(final boolean tokenDefaultKycStatus) {
        this.tokenDefaultKycStatus = tokenDefaultKycStatus;
        return this;
    }

    public HTSPrecompileResult withIsFrozen(final boolean isFrozen) {
        this.isFrozen = isFrozen;
        return this;
    }

    public HTSPrecompileResult withTokenKeyValue(final Key key) {
        this.key = key;
        return this;
    }

    public HTSPrecompileResult withIsToken(final boolean isToken) {
        this.isToken = isToken;
        return this;
    }

    public HTSPrecompileResult withTokenType(final int tokenType) {
        this.tokenType = tokenType;
        return this;
    }

    @Override
    public Bytes getBytes() {
        if (ParsingConstants.FunctionType.ERC_OWNER.equals(functionType)) {
            return Bytes.wrap(expandByteArrayTo32Length(owner));
        } else if (ParsingConstants.FunctionType.ERC_GET_APPROVED.equals(functionType)) {
            return Bytes.wrap(expandByteArrayTo32Length(approved));
        }

        final Tuple result =
                switch (functionType) {
                    case HAPI_MINT -> Tuple.of(status.getNumber(), BigInteger.valueOf(totalSupply), serialNumbers);
                    case HAPI_BURN -> Tuple.of(status.getNumber(), BigInteger.valueOf(totalSupply));
                    case ERC_TOTAL_SUPPLY -> Tuple.singleton(BigInteger.valueOf(totalSupply));
                    case ERC_DECIMALS -> Tuple.singleton(decimals);
                    case ERC_BALANCE -> Tuple.singleton(BigInteger.valueOf(balance));
                    case ERC_NAME -> Tuple.singleton(name);
                    case ERC_SYMBOL -> Tuple.singleton(symbol);
                    case ERC_TOKEN_URI -> Tuple.singleton(metadata);
                    case ERC_TRANSFER -> Tuple.singleton(ercFungibleTransferStatus);
                    case ERC_IS_APPROVED_FOR_ALL -> Tuple.singleton(isApprovedForAllStatus);
                    case ERC_ALLOWANCE -> Tuple.singleton(BigInteger.valueOf(allowance));
                    case HAPI_IS_APPROVED_FOR_ALL -> Tuple.of(status.getNumber(), isApprovedForAllStatus);
                    case HAPI_ALLOWANCE -> Tuple.of(status.getNumber(), BigInteger.valueOf(allowance));
                    case HAPI_GET_APPROVED -> Tuple.of(status.getNumber(), expandByteArrayTo32Length(approved));
                    case HAPI_GET_TOKEN_INFO -> getTupleForGetTokenInfo();
                    case HAPI_GET_TOKEN_INFO_V2 -> getTupleForGetTokenInfoV2();
                    case HAPI_GET_FUNGIBLE_TOKEN_INFO -> getTupleForGetFungibleTokenInfo();
                    case HAPI_GET_FUNGIBLE_TOKEN_INFO_V2 -> getTupleForGetFungibleTokenInfoV2();
                    case HAPI_GET_NON_FUNGIBLE_TOKEN_INFO -> getTupleForGetNonFungibleTokenInfo();
                    case HAPI_GET_NON_FUNGIBLE_TOKEN_INFO_V2 -> getTupleForGetNonFungibleTokenInfoV2();
                    case HAPI_IS_KYC -> Tuple.of(status.getNumber(), isKyc);
                    case GET_TOKEN_DEFAULT_FREEZE_STATUS -> Tuple.of(status.getNumber(), tokenDefaultFreezeStatus);
                    case GET_TOKEN_DEFAULT_KYC_STATUS -> Tuple.of(status.getNumber(), tokenDefaultKycStatus);
                    case HAPI_IS_FROZEN -> Tuple.of(status.getNumber(), isFrozen);
                    case HAPI_GET_TOKEN_CUSTOM_FEES -> getTupleForTokenGetCustomFees();
                    case HAPI_IS_TOKEN -> Tuple.of(status.getNumber(), isToken);
                    case HAPI_GET_TOKEN_TYPE -> Tuple.of(status.getNumber(), tokenType);
                    case HAPI_GET_TOKEN_EXPIRY_INFO -> getTupleForTokenGetExpiryInfo();
                    case HAPI_GET_TOKEN_KEY -> getKeyValueTupleWithResponseCode(status.getNumber(), key);
                    default -> Tuple.singleton(status.getNumber());
                };

        return Bytes.wrap(tupleType.encode(result).array());
    }

    private Tuple getTupleForGetTokenInfo() {
        return Tuple.of(status.getNumber(), getTupleForTokenInfo());
    }

    private Tuple getTupleForGetTokenInfoV2() {
        return Tuple.of(status.getNumber(), getTupleForTokenInfoV2());
    }

    private Tuple getTupleForGetFungibleTokenInfo() {
        return Tuple.of(status.getNumber(), Tuple.of(getTupleForTokenInfo(), decimals));
    }

    private Tuple getTupleForGetFungibleTokenInfoV2() {
        return Tuple.of(status.getNumber(), Tuple.of(getTupleForTokenInfoV2(), decimals));
    }

    private Tuple getTupleForGetNonFungibleTokenInfo() {
        return Tuple.of(
                status.getNumber(),
                Tuple.of(
                        getTupleForTokenInfo(),
                        nonFungibleTokenInfo.getNftID().getSerialNumber(),
                        expandByteArrayTo32Length(Utils.asAddress(nonFungibleTokenInfo.getAccountID())),
                        nonFungibleTokenInfo.getCreationTime().getSeconds(),
                        nonFungibleTokenInfo.getMetadata().toByteArray(),
                        expandByteArrayTo32Length(Utils.asAddress(nonFungibleTokenInfo.getSpenderId()))));
    }

    private Tuple getTupleForGetNonFungibleTokenInfoV2() {
        return Tuple.of(
                status.getNumber(),
                Tuple.of(
                        getTupleForTokenInfoV2(),
                        nonFungibleTokenInfo.getNftID().getSerialNumber(),
                        expandByteArrayTo32Length(Utils.asAddress(nonFungibleTokenInfo.getAccountID())),
                        nonFungibleTokenInfo.getCreationTime().getSeconds(),
                        nonFungibleTokenInfo.getMetadata().toByteArray(),
                        expandByteArrayTo32Length(Utils.asAddress(nonFungibleTokenInfo.getSpenderId()))));
    }

    private Tuple getTupleForTokenGetCustomFees() {
        return getTupleForTokenCustomFees(status.getNumber());
    }

    private Tuple getTupleForTokenGetExpiryInfo() {
        return getTupleForTokenExpiryInfo(status.getNumber());
    }

    private Tuple getTupleForTokenCustomFees(final int responseCode) {
        final var fixedFees = new ArrayList<Tuple>();
        final var fractionalFees = new ArrayList<Tuple>();
        final var royaltyFees = new ArrayList<Tuple>();

        for (final var customFee : customFees) {
            extractFees(fixedFees, fractionalFees, royaltyFees, customFee);
        }
        return Tuple.of(
                responseCode,
                fixedFees.toArray(new Tuple[fixedFees.size()]),
                fractionalFees.toArray(new Tuple[fractionalFees.size()]),
                royaltyFees.toArray(new Tuple[royaltyFees.size()]));
    }

    private Tuple getTupleForTokenExpiryInfo(final int responseCode) {
        return Tuple.of(
                responseCode,
                Tuple.of(expiry, expandByteArrayTo32Length(Utils.asAddress(autoRenewAccount)), autoRenewPeriod));
    }

    private void extractFees(
            final ArrayList<Tuple> fixedFees,
            final ArrayList<Tuple> fractionalFees,
            final ArrayList<Tuple> royaltyFees,
            final CustomFee customFee) {
        final var feeCollector = expandByteArrayTo32Length(Utils.asAddress(customFee.getFeeCollectorAccountId()));
        if (customFee.getFixedFee().getAmount() > 0) {
            fixedFees.add(getFixedFeeTuple(customFee.getFixedFee(), feeCollector));
        } else if (customFee.getFractionalFee().getMinimumAmount() > 0
                || customFee.getFractionalFee().getFractionalAmount().getNumerator() > 0) {
            fractionalFees.add(getFractionalFeeTuple(customFee.getFractionalFee(), feeCollector));
        } else if (customFee.getRoyaltyFee().getExchangeValueFraction().getNumerator() > 0) {
            royaltyFees.add(getRoyaltyFeeTuple(customFee.getRoyaltyFee(), feeCollector));
        }
    }

    private Tuple getTupleForTokenInfo() {
        final var fixedFees = new ArrayList<Tuple>();
        final var fractionalFees = new ArrayList<Tuple>();
        final var royaltyFees = new ArrayList<Tuple>();

        for (final var customFee : tokenInfo.getCustomFeesList()) {
            extractFees(fixedFees, fractionalFees, royaltyFees, customFee);
        }
        return Tuple.from(
                getHederaTokenTuple(),
                tokenInfo.getTotalSupply(),
                tokenInfo.getDeleted(),
                tokenInfo.getDefaultKycStatus().getNumber() == 1,
                tokenInfo.getPauseStatus().getNumber() == 1,
                fixedFees.toArray(new Tuple[fixedFees.size()]),
                fractionalFees.toArray(new Tuple[fractionalFees.size()]),
                royaltyFees.toArray(new Tuple[royaltyFees.size()]),
                Bytes.wrap(tokenInfo.getLedgerId().toByteArray()).toString());
    }

    private Tuple getTupleForTokenInfoV2() {
        final var fixedFees = new ArrayList<Tuple>();
        final var fractionalFees = new ArrayList<Tuple>();
        final var royaltyFees = new ArrayList<Tuple>();

        for (final var customFee : tokenInfo.getCustomFeesList()) {
            extractFees(fixedFees, fractionalFees, royaltyFees, customFee);
        }
        return Tuple.from(
                getHederaTokenTupleV2(),
                tokenInfo.getTotalSupply(),
                tokenInfo.getDeleted(),
                tokenInfo.getDefaultKycStatus().getNumber() == 1,
                tokenInfo.getPauseStatus().getNumber() == 1,
                fixedFees.toArray(new Tuple[fixedFees.size()]),
                fractionalFees.toArray(new Tuple[fractionalFees.size()]),
                royaltyFees.toArray(new Tuple[royaltyFees.size()]),
                Bytes.wrap(tokenInfo.getLedgerId().toByteArray()).toString());
    }

    private Tuple getFixedFeeTuple(final FixedFee fixedFee, final byte[] feeCollector) {
        return Tuple.of(
                fixedFee.getAmount(),
                expandByteArrayTo32Length(Utils.asAddress(fixedFee.getDenominatingTokenId())),
                fixedFee.getDenominatingTokenId().getTokenNum() == 0,
                false,
                feeCollector);
    }

    private Tuple getFractionalFeeTuple(final FractionalFee fractionalFee, final byte[] feeCollector) {
        return Tuple.of(
                fractionalFee.getFractionalAmount().getNumerator(),
                fractionalFee.getFractionalAmount().getDenominator(),
                fractionalFee.getMinimumAmount(),
                fractionalFee.getMaximumAmount(),
                fractionalFee.getNetOfTransfers(),
                feeCollector);
    }

    private Tuple getRoyaltyFeeTuple(final RoyaltyFee royaltyFee, final byte[] feeCollector) {
        return Tuple.of(
                royaltyFee.getExchangeValueFraction().getNumerator(),
                royaltyFee.getExchangeValueFraction().getDenominator(),
                royaltyFee.getFallbackFee().getAmount(),
                expandByteArrayTo32Length(
                        Utils.asAddress(royaltyFee.getFallbackFee().getDenominatingTokenId())),
                royaltyFee.getFallbackFee().getDenominatingTokenId().getTokenNum() == 0,
                feeCollector);
    }

    private Tuple getHederaTokenTuple() {
        expiry = tokenInfo.getExpiry().getSeconds();
        autoRenewPeriod = tokenInfo.getAutoRenewPeriod().getSeconds();
        final var expiryTuple = Tuple.of(
                expiry, expandByteArrayTo32Length(Utils.asAddress(tokenInfo.getAutoRenewAccount())), autoRenewPeriod);

        return Tuple.from(
                tokenInfo.getName(),
                tokenInfo.getSymbol(),
                expandByteArrayTo32Length(Utils.asAddress(tokenInfo.getTreasury())),
                tokenInfo.getMemo(),
                tokenInfo.getSupplyType().getNumber() == 1,
                tokenInfo.getMaxSupply(),
                tokenInfo.getDefaultFreezeStatus().getNumber() == 1,
                getTokenKeysTuples(),
                expiryTuple);
    }

    private Tuple getHederaTokenTupleV2() {
        expiry = tokenInfo.getExpiry().getSeconds();
        autoRenewPeriod = tokenInfo.getAutoRenewPeriod().getSeconds();
        final var expiryTuple = Tuple.of(
                expiry, expandByteArrayTo32Length(Utils.asAddress(tokenInfo.getAutoRenewAccount())), autoRenewPeriod);

        return Tuple.from(
                tokenInfo.getName(),
                tokenInfo.getSymbol(),
                expandByteArrayTo32Length(Utils.asAddress(tokenInfo.getTreasury())),
                tokenInfo.getMemo(),
                tokenInfo.getSupplyType().getNumber() == 1,
                tokenInfo.getMaxSupply(),
                tokenInfo.getDefaultFreezeStatus().getNumber() == 1,
                getTokenKeysTuplesV2(),
                expiryTuple,
                tokenInfo.getMetadata().toByteArray());
    }

    private Tuple[] getTokenKeysTuples() {
        return buildTokenKeysTuples();
    }

    private Tuple[] getTokenKeysTuplesV2() {
        return buildTokenKeysTuples(
                getKeyTuple(BigInteger.valueOf(TokenKeyType.METADATA_KEY.value()), tokenInfo.getMetadataKey()));
    }

    private Tuple[] buildTokenKeysTuples(final Tuple... additionalKeys) {
        // -1 for the additional key for METADATA
        final int existingKeysLength = TokenKeyType.values().length - 1;
        final int additionalKeysLength = additionalKeys.length;
        final Tuple[] tokenKeys = new Tuple[existingKeysLength + additionalKeysLength];

        tokenKeys[0] = getKeyTuple(BigInteger.valueOf(TokenKeyType.ADMIN_KEY.value()), tokenInfo.getAdminKey());
        tokenKeys[1] = getKeyTuple(BigInteger.valueOf(TokenKeyType.KYC_KEY.value()), tokenInfo.getKycKey());
        tokenKeys[2] = getKeyTuple(BigInteger.valueOf(TokenKeyType.FREEZE_KEY.value()), tokenInfo.getFreezeKey());
        tokenKeys[3] = getKeyTuple(BigInteger.valueOf(TokenKeyType.WIPE_KEY.value()), tokenInfo.getWipeKey());
        tokenKeys[4] = getKeyTuple(BigInteger.valueOf(TokenKeyType.SUPPLY_KEY.value()), tokenInfo.getSupplyKey());
        tokenKeys[5] =
                getKeyTuple(BigInteger.valueOf(TokenKeyType.FEE_SCHEDULE_KEY.value()), tokenInfo.getFeeScheduleKey());
        tokenKeys[6] = getKeyTuple(BigInteger.valueOf(TokenKeyType.PAUSE_KEY.value()), tokenInfo.getPauseKey());

        if (additionalKeys.length > 0) {
            System.arraycopy(additionalKeys, 0, tokenKeys, existingKeysLength, additionalKeysLength);
        }

        return tokenKeys;
    }

    private static Tuple getKeyTuple(final BigInteger keyType, final Key key) {
        return Tuple.of(keyType, getKeyValueTuple(key));
    }

    private static Tuple getKeyValueTupleWithResponseCode(final int responseCode, final Key key) {
        return Tuple.of(responseCode, getKeyValueTuple(key));
    }

    private static Tuple getKeyValueTuple(final Key key) {
        return Tuple.of(
                false,
                key.getContractID().getContractNum() > 0
                        ? expandByteArrayTo32Length(Utils.asAddress(key.getContractID()))
                        : new byte[32],
                key.getEd25519().toByteArray(),
                key.getECDSASecp256K1().toByteArray(),
                key.getDelegatableContractId().getContractNum() > 0
                        ? expandByteArrayTo32Length(Utils.asAddress(key.getDelegatableContractId()))
                        : new byte[32]);
    }

    private static String removeBrackets(final String type) {
        final var typeWithRemovedOpenBracket = type.replace("(", "");
        return typeWithRemovedOpenBracket.replace(")", "");
    }

    public static byte[] expandByteArrayTo32Length(final byte[] bytesToExpand) {
        final byte[] expandedArray = new byte[32];

        System.arraycopy(
                bytesToExpand, 0, expandedArray, expandedArray.length - bytesToExpand.length, bytesToExpand.length);
        return expandedArray;
    }
}

// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.9.0;
pragma experimental ABIEncoderV2;
import "./FeeHelper.sol";
import "./KeyHelper.sol";
import "./HederaTokenService.sol";
import "./IHederaTokenService.sol";
import "./HederaResponseCodes.sol";

contract UpdateTokenInfoContract is HederaTokenService, FeeHelper, KeyHelper {
    string name = "tokenName";
    string symbol = "tokenSymbol";
    string memo = "memo";

    // TEST-001
    function updateTokenWithAllFields(
        address tokenID,
        address treasury,
        bytes memory ed25519,
        bytes memory ecdsa,
        address contractID,
        address autoRenewAccount,
        int64 autoRenewPeriod,
        string memory _name,
        string memory _symbol,
        string memory _memo) public payable {
        IHederaTokenService.TokenKey[] memory keys = new IHederaTokenService.TokenKey[](5);
        keys[0] = getSingleKey(KeyType.ADMIN, KeyType.KYC, KeyValueType.ED25519, ed25519); //admin 3
        keys[1] = getSingleKey(KeyType.FREEZE, KeyType.WIPE, KeyValueType.SECP256K1, ecdsa); //freeze 12
        keys[2] = getSingleKey(KeyType.SUPPLY, KeyValueType.CONTRACT_ID, contractID); //supply 16
        keys[3] = getSingleKey(KeyType.PAUSE, KeyValueType.CONTRACT_ID, contractID); //pause 64
        keys[4] = getSingleKey(KeyType.FEE, KeyValueType.DELEGETABLE_CONTRACT_ID, contractID); //schedule 32

        name = _name;
        symbol = _symbol;
        memo = _memo;
        IHederaTokenService.HederaToken memory token =
                        tokenWithExpiry(treasury, 0, autoRenewAccount, autoRenewPeriod, keys);

        int responseCode = HederaTokenService.updateTokenInfo(tokenID, token);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of tokenInfo failed!");
        }
    }

    // TEST-002
    function updateTokenTreasury(
        address tokenID,
        address treasury) public payable {


        IHederaTokenService.HederaToken memory token;
        token.name = name;
        token.symbol = symbol;
        token.treasury = treasury;
        token.memo = memo;

        int responseCode = HederaTokenService.updateTokenInfo(tokenID,token);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of tokenInfo.treasury failed!");
        }
    }

    // TEST-003
    function checkNameAndSymbolLength(
        address tokenID,
        address treasury,
        string memory _name,
        string memory _symbol) public payable {


        IHederaTokenService.HederaToken memory token;
        token.name = _name;
        token.symbol = _symbol;
        token.treasury = treasury;
        token.memo = memo;

        int responseCode = HederaTokenService.updateTokenInfo(tokenID,token);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of tokenInfo name and symbol failed!");
        }
    }

    // TEST-004
    function updateTokenWithKeys(
        address tokenID,
        address treasury,
        bytes memory ed25519,
        bytes memory ecdsa,
        address contractID) public payable {
        IHederaTokenService.TokenKey[] memory keys = new IHederaTokenService.TokenKey[](5);
        keys[0] = getSingleKey(KeyType.ADMIN, KeyType.KYC, KeyValueType.ED25519, ed25519); //admin 3
        keys[1] = getSingleKey(KeyType.FREEZE, KeyType.WIPE, KeyValueType.SECP256K1, ecdsa); //freeze 12
        keys[2] = getSingleKey(KeyType.SUPPLY, KeyValueType.CONTRACT_ID, contractID); //supply 16
        keys[3] = getSingleKey(KeyType.PAUSE, KeyValueType.CONTRACT_ID, contractID); //pause 64
        keys[4] = getSingleKey(KeyType.FEE, KeyValueType.DELEGETABLE_CONTRACT_ID, contractID); //schedule 32

        IHederaTokenService.HederaToken memory token;
        token.treasury = treasury;
        token.name = name;
        token.symbol = symbol;
        token.tokenKeys = keys;
        token.memo = memo;


        int responseCode = HederaTokenService.updateTokenInfo(tokenID,token);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of tokenInfo keys failed!");
        }
    }

    // TEST-005
    function updateTokenWithInvalidKeyValues(
        address tokenID,
        address autoRenewAccount,
        int64 autoRenewPeriod
    ) public payable  {
        // create the invalid key
        IHederaTokenService.TokenKey[] memory keys = new IHederaTokenService.TokenKey[](1);
        IHederaTokenService.TokenKey memory invalidKey;
        invalidKey.keyType = 4;
        IHederaTokenService.KeyValue memory invalidKeyValue;
        invalidKeyValue.contractId = address(this);
        invalidKeyValue.inheritAccountKey = true;
        invalidKey.key = invalidKeyValue;
        keys[0] = invalidKey;

        IHederaTokenService.HederaToken memory token =
                        tokenWithExpiry(address(this), 0, autoRenewAccount, autoRenewPeriod, keys);

        int responseCode = HederaTokenService.updateTokenInfo(tokenID,token);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ();
        }
    }

    // TEST-006
    function tokenUpdateKeys(
        address token,
        bytes memory ed25519,
        bytes memory ecdsa,
        address contractID) public payable {

        IHederaTokenService.TokenKey[] memory keys = new IHederaTokenService.TokenKey[](5);
        keys[0] = getSingleKey(KeyType.ADMIN, KeyType.KYC, KeyValueType.ED25519, ed25519); //admin 3
        keys[1] = getSingleKey(KeyType.FREEZE, KeyType.WIPE, KeyValueType.SECP256K1, ecdsa); //freeze 12
        keys[2] = getSingleKey(KeyType.SUPPLY, KeyValueType.CONTRACT_ID, contractID); //supply 16
        keys[3] = getSingleKey(KeyType.PAUSE, KeyValueType.CONTRACT_ID, contractID); //pause 64
        keys[4] = getSingleKey(KeyType.FEE, KeyValueType.DELEGETABLE_CONTRACT_ID, contractID); //schedule 32

        int responseCode = super.updateTokenKeys(token, keys);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of token keys failed!");
        }
    }


    //TEST-007
    function getKeyFromToken(address token, uint keyType) external
    returns(IHederaTokenService.KeyValue memory){
        (int responseCode,IHederaTokenService.KeyValue memory
            key) = HederaTokenService.getTokenKey(token, keyType);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ();
        }

        return key;
    }

    //TEST-008
    function updateTokenWithoutNameSymbolMemo(
        address tokenID,
        address treasury,
        bytes memory ed25519,
        bytes memory ecdsa,
        address contractID,
        address autoRenewAccount,
        int64 autoRenewPeriod) public payable {
        IHederaTokenService.TokenKey[] memory keys = new IHederaTokenService.TokenKey[](5);
        keys[0] = getSingleKey(KeyType.ADMIN, KeyType.KYC, KeyValueType.ED25519, ed25519); //admin 3
        keys[1] = getSingleKey(KeyType.FREEZE, KeyType.WIPE, KeyValueType.SECP256K1, ecdsa); //freeze 12
        keys[2] = getSingleKey(KeyType.SUPPLY, KeyValueType.CONTRACT_ID, contractID); //supply 16
        keys[3] = getSingleKey(KeyType.PAUSE, KeyValueType.CONTRACT_ID, contractID); //pause 64
        keys[4] = getSingleKey(KeyType.FEE, KeyValueType.DELEGETABLE_CONTRACT_ID, contractID); //schedule 32

        IHederaTokenService.Expiry memory expiry;
        expiry.second = 0;
        expiry.autoRenewAccount = autoRenewAccount;
        expiry.autoRenewPeriod = autoRenewPeriod;

        IHederaTokenService.HederaToken memory token;
        token.treasury = treasury;
        token.tokenKeys = keys;
        token.expiry = expiry;

        int responseCode = HederaTokenService.updateTokenInfo(tokenID, token);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of tokenInfo failed!");
        }
    }

    /** --- HELPERS --- */

    function tokenWithExpiry(
        address treasury,
        int64 second,
        address autoRenewAccount,
        int64 autoRenewPeriod,
        IHederaTokenService.TokenKey[] memory keys
    ) internal view returns (IHederaTokenService.HederaToken memory token) {

        IHederaTokenService.Expiry memory expiry;
        expiry.second = second;
        expiry.autoRenewAccount = autoRenewAccount;
        expiry.autoRenewPeriod = autoRenewPeriod;

        token.name = name;
        token.symbol = symbol;
        token.treasury = treasury;
        token.tokenKeys = keys;
        token.expiry = expiry;
        token.memo = memo;
    }
}

// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.9.0;
pragma experimental ABIEncoderV2;

/**
 * Forwards an arbitrary caller-supplied TokenKey[] to the HTS updateTokenKeys and updateTokenInfo functions and to
 * the create functions at 0x167 and 0x16c, so tests can exercise key types (empty list, zero, undefined or mixed bits)
 * that the shared HederaTokenService/KeyHelper sources cannot express. The contract is the treasury of a created token
 * and may hold the keys of a token it updates. It returns the response code of the precompile (and the new token
 * address for a create) and reverts only if the call itself fails. Self-contained on purpose.
 */
contract TokenKeysContract {
    address constant HTS_167 = address(0x167);
    address constant HTS_16C = address(0x16c);

    struct KeyValue {
        bool inheritAccountKey;
        address contractId;
        bytes ed25519;
        bytes ECDSA_secp256k1;
        address delegatableContractId;
    }

    struct TokenKey {
        uint256 keyType;
        KeyValue key;
    }

    struct Expiry {
        int64 second;
        address autoRenewAccount;
        int64 autoRenewPeriod;
    }

    struct HederaToken {
        string name;
        string symbol;
        address treasury;
        string memo;
        bool tokenSupplyType;
        int64 maxSupply;
        bool freezeDefault;
        TokenKey[] tokenKeys;
        Expiry expiry;
    }

    struct HederaTokenWithMetadata {
        string name;
        string symbol;
        address treasury;
        string memo;
        bool tokenSupplyType;
        int64 maxSupply;
        bool freezeDefault;
        TokenKey[] tokenKeys;
        Expiry expiry;
        bytes metadata;
    }

    function updateTokenKeys(address token, TokenKey[] memory keys) external returns (int64) {
        return updateKeysAt(HTS_167, token, keys);
    }

    function updateTokenKeysWithMetadata(address token, TokenKey[] memory keys) external returns (int64) {
        return updateKeysAt(HTS_16C, token, keys);
    }

    function updateKeysAt(address hts, address token, TokenKey[] memory keys) private returns (int64) {
        return callUpdate(hts, abi.encodeWithSignature(
            "updateTokenKeys(address,(uint256,(bool,address,bytes,bytes,address))[])", token, keys));
    }

    // Updates nothing but the keys: blank name, symbol and memo, a zero treasury and a zero expiry mean "unchanged"
    function updateTokenInfo(address token, TokenKey[] memory keys) external returns (int64) {
        HederaToken memory info = HederaToken("", "", address(0), "", false, 0, false, keys, Expiry(0, address(0), 0));
        return callUpdate(HTS_167, abi.encodeWithSignature(
            "updateTokenInfo(address,(string,string,address,string,bool,int64,bool,(uint256,(bool,address,bytes,bytes,address))[],(int64,address,int64)))",
            token, info));
    }

    function updateTokenInfoWithMetadata(address token, TokenKey[] memory keys) external returns (int64) {
        HederaTokenWithMetadata memory info = HederaTokenWithMetadata(
            "", "", address(0), "", false, 0, false, keys, Expiry(0, address(0), 0), "");
        return callUpdate(HTS_16C, abi.encodeWithSignature(
            "updateTokenInfo(address,(string,string,address,string,bool,int64,bool,(uint256,(bool,address,bytes,bytes,address))[],(int64,address,int64),bytes))",
            token, info));
    }

    // Returns the response code of the precompile, so a caller can tell a rejection from a success; only a failure of
    // the call itself (a revert or halt in the precompile) reverts here
    function callUpdate(address hts, bytes memory encodedCall) private returns (int64) {
        (bool success, bytes memory result) = hts.call(encodedCall);
        if (!success) {
            revert("Update call failed!");
        }
        return abi.decode(result, (int64));
    }

    function createFungibleToken(TokenKey[] memory keys) external payable returns (int64, address) {
        (bool success, bytes memory result) = HTS_167.call{value: msg.value}(abi.encodeWithSignature(
            "createFungibleToken((string,string,address,string,bool,int64,bool,(uint256,(bool,address,bytes,bytes,address))[],(int64,address,int64)),int64,int32)",
            tokenWith(keys), int64(200), int32(8)));
        return resultOf(success, result);
    }

    function createNonFungibleToken(TokenKey[] memory keys) external payable returns (int64, address) {
        (bool success, bytes memory result) = HTS_167.call{value: msg.value}(abi.encodeWithSignature(
            "createNonFungibleToken((string,string,address,string,bool,int64,bool,(uint256,(bool,address,bytes,bytes,address))[],(int64,address,int64)))",
            tokenWith(keys)));
        return resultOf(success, result);
    }

    function createFungibleTokenWithMetadata(TokenKey[] memory keys) external payable returns (int64, address) {
        HederaToken memory token = tokenWith(keys);
        HederaTokenWithMetadata memory withMetadata = HederaTokenWithMetadata(
            token.name, token.symbol, token.treasury, token.memo, token.tokenSupplyType, token.maxSupply,
            token.freezeDefault, token.tokenKeys, token.expiry, "");
        (bool success, bytes memory result) = HTS_16C.call{value: msg.value}(abi.encodeWithSignature(
            "createFungibleToken((string,string,address,string,bool,int64,bool,(uint256,(bool,address,bytes,bytes,address))[],(int64,address,int64),bytes),int64,int32)",
            withMetadata, int64(200), int32(8)));
        return resultOf(success, result);
    }

    function tokenWith(TokenKey[] memory keys) private view returns (HederaToken memory) {
        return HederaToken(
            "tokenName", "tokenSymbol", address(this), "", false, 0, false, keys, Expiry(0, address(this), 8000000));
    }

    function resultOf(bool success, bytes memory result) private pure returns (int64, address) {
        if (!success) {
            revert("Create call failed!");
        }
        return abi.decode(result, (int64, address));
    }
}

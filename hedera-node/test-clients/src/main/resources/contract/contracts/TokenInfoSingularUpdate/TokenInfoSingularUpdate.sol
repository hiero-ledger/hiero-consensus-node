// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;
pragma experimental ABIEncoderV2;

import "./KeyHelper.sol";

contract TokenInfoSingularUpdate is HederaTokenService, KeyHelper {
    function updateTokenName(address tokenAddress, string memory name) external {
        IHederaTokenService.HederaToken memory token;
        token.name = name;
        int responseCode = HederaTokenService.updateTokenInfo(tokenAddress, token);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of tokenInfo failed!");
        }
    }

    function updateTokenSymbol(address tokenAddress, string memory symbol) external {
        IHederaTokenService.HederaToken memory token;
        token.symbol = symbol;
        int responseCode = HederaTokenService.updateTokenInfo(tokenAddress, token);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of tokenInfo failed!");
        }
    }

    function updateTokenMemo(address tokenAddress, string memory memo) external {
        IHederaTokenService.HederaToken memory token;
        token.memo = memo;
        int responseCode = HederaTokenService.updateTokenInfo(tokenAddress, token);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of tokenInfo failed!");
        }
    }

    function updateTokenTreasury(address tokenAddress, address treasury) external {
        IHederaTokenService.HederaToken memory token;
        token.treasury = treasury;
        int responseCode = HederaTokenService.updateTokenInfo(tokenAddress, token);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of tokenInfo failed!");
        }
    }

    function updateTokenAutoRenewAccount(address tokenAddress, address autoRenewAccount) external {
        IHederaTokenService.HederaToken memory token;
        IHederaTokenService.Expiry memory expiry;

        expiry.autoRenewAccount = autoRenewAccount;
        token.expiry = expiry;
        int responseCode = HederaTokenService.updateTokenInfo(tokenAddress, token);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of tokenInfo failed!");
        }
    }

    function updateTokenAutoRenewPeriod(address tokenAddress, int64 autoRenewPeriod) external {
        IHederaTokenService.HederaToken memory token;
        IHederaTokenService.Expiry memory expiry;

        expiry.autoRenewPeriod = autoRenewPeriod;
        token.expiry = expiry;

        int responseCode = HederaTokenService.updateTokenInfo(tokenAddress, token);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of tokenInfo failed!");
        }
    }

    function updateTokenKeyEd(address tokenAddress, bytes memory newKey, KeyType keyType) external {
        IHederaTokenService.HederaToken memory token;
        token.tokenKeys = getCustomSingleTypeKeys(keyType, KeyValueType.ED25519, newKey);
        int responseCode = HederaTokenService.updateTokenInfo(tokenAddress, token);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of tokenInfo failed!");
        }
    }

    function updateTokenKeyContractId(address tokenAddress, address contractId, KeyType keyType) external {
        IHederaTokenService.HederaToken memory token;
        IHederaTokenService.TokenKey[] memory tokenKeys = new IHederaTokenService.TokenKey[](1);
        tokenKeys[0] = KeyHelper.getSingleKey(keyType, KeyValueType.CONTRACT_ID, contractId);
        token.tokenKeys = tokenKeys;
        int responseCode = HederaTokenService.updateTokenInfo(tokenAddress, token);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of tokenInfo failed!");
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;

import "./HederaScheduleService.sol";
import "./HederaTokenService.sol";
import "./HederaResponseCodes.sol";
import "./KeyHelper.sol";
pragma experimental ABIEncoderV2;

contract HIP756Contract is HederaScheduleService, KeyHelper {

    function scheduleCreateFT(address autoRenew, address treasury) external payable returns (int64 responseCode, address scheduleAddress) {
        IHederaTokenService.HederaToken memory token;
        token.treasury = treasury;
        IHederaTokenService.Expiry memory expiry;
        expiry.autoRenewAccount = autoRenew;
        token.expiry = expiry;

        token.name = "test";
        token.symbol = "TTT";

        bytes memory tokenCreateBytes = abi.encodeWithSelector(IHederaTokenService.createFungibleToken.selector, token, 1000, 10);
        (responseCode, scheduleAddress) = scheduleNative( address(0x167), tokenCreateBytes, address(this));
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert("Failed to associate");
        }
    }

    function scheduleCreateFTWithDesignatedPayer(address autoRenew, address treasury, address payer) external payable returns (int64 responseCode, address scheduleAddress) {
        IHederaTokenService.HederaToken memory token;
        token.treasury = treasury;
        IHederaTokenService.Expiry memory expiry;
        expiry.autoRenewAccount = autoRenew;
        token.expiry = expiry;

        token.name = "test with designated payer";
        token.symbol = "TTTP";

        bytes memory tokenCreateBytes = abi.encodeWithSelector(IHederaTokenService.createFungibleToken.selector, token, 1000, 10);
        (responseCode, scheduleAddress) = scheduleNative( address(0x167), tokenCreateBytes, payer);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert("Failed to associate");
        }
    }

    function scheduleCreateNFT(address autoRenew, address treasury) external payable returns (int64 responseCode, address scheduleAddress) {
        IHederaTokenService.HederaToken memory token;
        token.name = "nft";
        token.symbol = "nft";
        token.treasury = address(treasury);
        IHederaTokenService.Expiry memory expiry;
        expiry.autoRenewAccount = autoRenew;
        token.expiry = expiry;
        token.tokenKeys = new IHederaTokenService.TokenKey[](1);
        IHederaTokenService.TokenKey memory tokenSupplyKey = KeyHelper.getSingleKey(KeyHelper.KeyType.SUPPLY, KeyHelper.KeyValueType.CONTRACT_ID, address(this));
        token.tokenKeys[0] = tokenSupplyKey;
        bytes memory tokenCreateBytes = abi.encodeWithSelector(IHederaTokenService.createNonFungibleToken.selector, token);
        (responseCode, scheduleAddress) = scheduleNative(address(0x167), tokenCreateBytes, address(this));
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function scheduleCreateNFTWithDesignatedPayer(
        address autoRenew,
        address treasury,
        address payer) external payable returns (int64 responseCode, address scheduleAddress) {
        IHederaTokenService.HederaToken memory token;
        token.name = "nft with designated payer";
        token.symbol = "nftp";
        token.treasury = address(treasury);
        IHederaTokenService.Expiry memory expiry;
        expiry.autoRenewAccount = autoRenew;
        token.expiry = expiry;
        token.tokenKeys = new IHederaTokenService.TokenKey[](1);
        IHederaTokenService.TokenKey memory tokenSupplyKey = KeyHelper.getSingleKey(KeyHelper.KeyType.SUPPLY, KeyHelper.KeyValueType.CONTRACT_ID, address(this));
        token.tokenKeys[0] = tokenSupplyKey;
        bytes memory tokenCreateBytes = abi.encodeWithSelector(IHederaTokenService.createNonFungibleToken.selector, token);
        (responseCode, scheduleAddress) = scheduleNative(address(0x167), tokenCreateBytes, payer);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function scheduleUpdateTreasuryAndAutoRenewAcc(
        address tokenAddress,
        address treasuryAddress,
        address autoRenewAddress,
        string memory name,
        string memory symbol,
        string memory memo) external returns (int64 responseCode, address scheduleAddress) {
        IHederaTokenService.HederaToken memory token;
        token.name = name;
        token.symbol = symbol;
        token.treasury = treasuryAddress;
        token.memo = memo;
        IHederaTokenService.Expiry memory expiry;
        expiry.autoRenewAccount = autoRenewAddress;
        token.expiry = expiry;

        bytes memory tokenUpdateBytes = abi.encodeWithSelector(IHederaTokenService.updateTokenInfo.selector, tokenAddress, token);
        (responseCode, scheduleAddress) = scheduleNative(address(0x167), tokenUpdateBytes, address(this));

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of tokenInfo.treasury failed!");
        }
    }

    function scheduleUpdateTreasuryAndAutoRenewAccWithDesignatedPayer(address tokenAddress, address treasuryAddress, address autoRenewAddress, string memory name, string memory symbol, string memory memo, address designatedPayer) external returns (int64 responseCode, address scheduleAddress) {
        IHederaTokenService.HederaToken memory token;
        token.name = name;
        token.symbol = symbol;
        token.treasury = treasuryAddress;
        token.memo = memo;
        IHederaTokenService.Expiry memory expiry;
        expiry.autoRenewAccount = autoRenewAddress;
        token.expiry = expiry;

        bytes memory tokenUpdateBytes = abi.encodeWithSelector(IHederaTokenService.updateTokenInfo.selector, tokenAddress, token);
        (responseCode, scheduleAddress) = scheduleNative(address(0x167), tokenUpdateBytes, designatedPayer);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of tokenInfo.treasury failed!");
        }
    }
}

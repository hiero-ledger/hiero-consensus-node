// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "../../HederaTokenService.sol";
import "../../ExpiryHelper.sol";
import "../../KeyHelper.sol";

contract ClassicQueriesXTest is HederaTokenService, ExpiryHelper, KeyHelper {

    function isFrozenPublic(address token, address account) public returns (int responseCode, bool frozen) {
        (responseCode, frozen) = HederaTokenService.isFrozen(token, account);
    }

    function isKycPublic(address token, address account) external returns (int64 responseCode, bool kycGranted) {
        (responseCode, kycGranted) = HederaTokenService.isKyc(token, account);
    }

    function getTokenCustomFeesPublic(address token) public returns (
        int64 responseCode,
        IHederaTokenService.FixedFee[] memory fixedFees,
        IHederaTokenService.FractionalFee[] memory fractionalFees,
        IHederaTokenService.RoyaltyFee[] memory royaltyFees) {
        (responseCode, fixedFees, fractionalFees, royaltyFees) = HederaTokenService.getTokenCustomFees(token);
    }

    function getTokenDefaultFreezeStatusPublic(address token) public returns (int responseCode, bool defaultFreezeStatus) {
        (responseCode, defaultFreezeStatus) = HederaTokenService.getTokenDefaultFreezeStatus(token);
    }

    function getTokenDefaultKycStatusPublic(address token) public returns (int responseCode, bool defaultKycStatus) {
        (responseCode, defaultKycStatus) = HederaTokenService.getTokenDefaultKycStatus(token);
    }

    function getTokenExpiryInfoPublic(address token)external returns (int responseCode, IHederaTokenService.Expiry memory expiryInfo) {
        (responseCode, expiryInfo) = HederaTokenService.getTokenExpiryInfo(token);
    }

    function getFungibleTokenInfoPublic(address token) public returns (int responseCode, IHederaTokenService.FungibleTokenInfo memory tokenInfo) {
        (responseCode, tokenInfo) = HederaTokenService.getFungibleTokenInfo(token);
    }

    function getTokenInfoPublic(address token) public returns (int responseCode, IHederaTokenService.TokenInfo memory tokenInfo) {
        (responseCode, tokenInfo) = HederaTokenService.getTokenInfo(token);
    }

    function getTokenKeyPublic(address token, uint keyType)
    public returns (int64 responseCode, IHederaTokenService.KeyValue memory key) {
        (responseCode, key) = HederaTokenService.getTokenKey(token, keyType);
    }

    function getNonFungibleTokenInfoPublic(address token, int64 serialNumber) public returns (int responseCode, IHederaTokenService.NonFungibleTokenInfo memory tokenInfo) {
        (responseCode, tokenInfo) = HederaTokenService.getNonFungibleTokenInfo(token, serialNumber);
    }

    function isTokenPublic(address token) public returns (int64 responseCode, bool isTokenFlag) {
        (responseCode, isTokenFlag) = HederaTokenService.isToken(token);
    }

    function getTokenTypePublic(address token) public returns (int64 responseCode, int32 tokenType) {
        (responseCode, tokenType) = HederaTokenService.getTokenType(token);
    }
}

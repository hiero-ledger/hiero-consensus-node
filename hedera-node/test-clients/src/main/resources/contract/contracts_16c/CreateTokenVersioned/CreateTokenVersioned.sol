// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./KeyHelper.sol";
import "./HederaResponseCodes.sol";
import "./HederaTokenService.sol";
import "./FeeHelper.sol";

contract CreateTokenVersioned is HederaTokenService, KeyHelper, FeeHelper {
    function createTokenWithMetadata() public payable returns (address createdAddress) {
        IHederaTokenService.HederaToken memory token;
        token.name = "testToken";
        token.metadata = bytes("testmeta");
        token.symbol = "test";
        token.treasury = address(this);
        token.tokenKeys = new IHederaTokenService.TokenKey[](0);

        (int responseCode, address tokenAddress) = HederaTokenService.createFungibleToken(token, 100, 4);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }

        createdAddress = tokenAddress;
    }

    function createTokenWithMetadataAndKey() public payable returns (address createdAddress) {
        IHederaTokenService.HederaToken memory token;
        token.name = "testToken";
        token.metadata = bytes("testmeta");
        token.symbol = "test";
        token.treasury = address(this);
        token.tokenKeys = new IHederaTokenService.TokenKey[](1);
        IHederaTokenService.TokenKey memory tokenKey = super.getSingleKey(KeyType.METADATA, KeyValueType.CONTRACT_ID, address(this));
        token.tokenKeys[0] = tokenKey;
        (int responseCode, address tokenAddress) = HederaTokenService.createFungibleToken(token, 100, 4);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }

        createdAddress = tokenAddress;
    }

    function createTokenWithMetadataAndCustomFees() public payable returns (address createdAddress) {
        IHederaTokenService.HederaToken memory token;
        token.name = "testToken";
        token.metadata = bytes("testmeta");
        token.symbol = "test";
        token.treasury = address(this);
        token.tokenKeys = new IHederaTokenService.TokenKey[](0);
        IHederaTokenService.FixedFee[] memory fixedFees = createSingleFixedFeeForHbars(10, address(this));
        IHederaTokenService.FractionalFee[] memory fractionalFees = new IHederaTokenService.FractionalFee[](0);
        (int responseCode, address tokenAddress) = HederaTokenService.createFungibleTokenWithCustomFees(token, 100, 8, fixedFees, fractionalFees);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }

        createdAddress = tokenAddress;
    }

    function createTokenWithMetadataAndKeyAndCustomFees() public payable returns (address createdAddress) {
        IHederaTokenService.HederaToken memory token;
        token.name = "testToken";
        token.metadata = bytes("testmeta");
        token.symbol = "test";
        token.treasury = address(this);
        token.tokenKeys = new IHederaTokenService.TokenKey[](1);
        IHederaTokenService.TokenKey memory tokenKey = super.getSingleKey(KeyType.METADATA, KeyValueType.CONTRACT_ID, address(this));
        token.tokenKeys[0] = tokenKey;
        IHederaTokenService.FixedFee[] memory fixedFees = createSingleFixedFeeForHbars(10, address(this));
        IHederaTokenService.FractionalFee[] memory fractionalFees = new IHederaTokenService.FractionalFee[](0);
        (int responseCode, address tokenAddress) = HederaTokenService.createFungibleTokenWithCustomFees(token, 100, 8, fixedFees, fractionalFees);


        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }

        createdAddress = tokenAddress;
    }

    function createNftWithMetadata() public payable returns (address createdAddress) {
        IHederaTokenService.HederaToken memory token;
        token.name = "nft";
        token.symbol = "nft";
        token.metadata = bytes("testmeta");
        token.treasury = address(this);
        token.tokenKeys = new IHederaTokenService.TokenKey[](1);
        IHederaTokenService.TokenKey memory tokenSupplyKey = super.getSingleKey(KeyType.SUPPLY, KeyValueType.CONTRACT_ID, address(this));
        token.tokenKeys[0] = tokenSupplyKey;
        (int responseCode, address tokenAddress) = HederaTokenService.createNonFungibleToken(token);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }

        createdAddress = tokenAddress;
    }


    function createNftWithMetaAndKey() public payable returns (address createdAddress) {
        IHederaTokenService.HederaToken memory token;
        token.name = "nft";
        token.symbol = "nft";
        token.metadata = bytes("testmeta");
        token.treasury = address(this);
        token.tokenKeys = new IHederaTokenService.TokenKey[](2);
        IHederaTokenService.TokenKey memory tokenKey = super.getSingleKey(KeyType.METADATA, KeyValueType.CONTRACT_ID, address(this));
        IHederaTokenService.TokenKey memory tokenSupplyKey = super.getSingleKey(KeyType.SUPPLY, KeyValueType.CONTRACT_ID, address(this));
        token.tokenKeys[0] = tokenKey;
        token.tokenKeys[1] = tokenSupplyKey;
        (int responseCode, address tokenAddress) = HederaTokenService.createNonFungibleToken(token);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }

        createdAddress = tokenAddress;
    }

    function createNftWithMetadataAndCustomFees() public payable returns (address createdAddress) {
        IHederaTokenService.HederaToken memory token;
        token.name = "nft";
        token.symbol = "nft";
        token.metadata = bytes("testmeta");
        token.treasury = address(this);
        token.tokenKeys = new IHederaTokenService.TokenKey[](1);
        IHederaTokenService.TokenKey memory tokenSupplyKey = super.getSingleKey(KeyType.SUPPLY, KeyValueType.CONTRACT_ID, address(this));
        token.tokenKeys[0] = tokenSupplyKey;
        IHederaTokenService.FixedFee[] memory fixedFees = createSingleFixedFeeForHbars(10, address(this));
        IHederaTokenService.RoyaltyFee[] memory royaltyFees = new IHederaTokenService.RoyaltyFee[](0);
        (int responseCode, address tokenAddress) = HederaTokenService.createNonFungibleTokenWithCustomFees(token, fixedFees, royaltyFees);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }

        createdAddress = tokenAddress;
    }

    function createNftWithMetaAndKeyAndCustomFees() public payable returns (address createdAddress) {
        IHederaTokenService.HederaToken memory token;
        token.name = "nft";
        token.symbol = "nft";
        token.metadata = bytes("testmeta");
        token.treasury = address(this);
        token.tokenKeys = new IHederaTokenService.TokenKey[](2);
        IHederaTokenService.TokenKey memory tokenMetaKey = super.getSingleKey(KeyType.METADATA, KeyValueType.CONTRACT_ID, address(this));
        IHederaTokenService.TokenKey memory tokenSupplyKey = super.getSingleKey(KeyType.SUPPLY, KeyValueType.CONTRACT_ID, address(this));
        token.tokenKeys[0] = tokenMetaKey;
        token.tokenKeys[1] = tokenSupplyKey;
        IHederaTokenService.FixedFee[] memory fixedFees = createSingleFixedFeeForHbars(10, address(this));
        IHederaTokenService.RoyaltyFee[] memory royaltyFees = new IHederaTokenService.RoyaltyFee[](0);
        (int responseCode, address tokenAddress) = HederaTokenService.createNonFungibleTokenWithCustomFees(token, fixedFees, royaltyFees);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }

        createdAddress = tokenAddress;
    }

    function updateTokenMetadata(address token, string memory metadata) public {
        IHederaTokenService.HederaToken memory tokenInfo;
        tokenInfo.metadata = bytes(metadata);

        (int256 responseCode) = HederaTokenService.updateTokenInfo(token, tokenInfo);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert();
        }
    }

    function updateTokenKeys(address token, bytes memory ed25519, address contractID) public {
        IHederaTokenService.TokenKey[] memory keys = new IHederaTokenService.TokenKey[](1);
        keys[0] = getSingleKey(KeyType.METADATA, KeyValueType.CONTRACT_ID, contractID); //metadata 7

        (int256 responseCode) = HederaTokenService.updateTokenKeys(token, keys);
        require(responseCode == HederaResponseCodes.SUCCESS);
    }
}

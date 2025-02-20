// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./FeeHelper.sol";

contract TokenMiscOperations is FeeHelper {

    string name = "tokenName";
    string symbol = "tokenSymbol";
    string memo = "memo";
    uint initialTotalSupply = 200;
    uint decimals = 8;

    function createTokenWithHbarsFixedFeeAndTransferIt(uint32 feeAmount, address feeCollector, address firstRecipient, address secondRecipient, address treasury) public payable returns (address createdTokenAddress) {
        IHederaTokenService.TokenKey[] memory keys = new IHederaTokenService.TokenKey[](1);
        keys[0] = getSingleKey(0, 1, "");

        createdTokenAddress = createTokenWithCustomFees(keys, super.createSingleFixedFeeForHbars(feeAmount, feeCollector), super.getEmptyFractionalFees(), treasury);

        int associateFeeCollectorResponse = HederaTokenService.associateToken(feeCollector, createdTokenAddress);
        if (associateFeeCollectorResponse != HederaResponseCodes.SUCCESS) {
            revert ();
        }

        int associateFirstRecipientResponse = HederaTokenService.associateToken(firstRecipient, createdTokenAddress);
        if (associateFirstRecipientResponse != HederaResponseCodes.SUCCESS) {
            revert ();
        }

        int associateSecondRecipientResponse = HederaTokenService.associateToken(secondRecipient, createdTokenAddress);
        if (associateSecondRecipientResponse != HederaResponseCodes.SUCCESS) {
            revert ();
        }

        int transferToFirstRecipientResponse = HederaTokenService.transferToken(createdTokenAddress, treasury, firstRecipient, 1);
        if (transferToFirstRecipientResponse != HederaResponseCodes.SUCCESS) {
            revert ();
        }

        int transferToSecondRecipientResponse = HederaTokenService.transferToken(createdTokenAddress, firstRecipient, secondRecipient, 1);
        if (transferToSecondRecipientResponse != HederaResponseCodes.SUCCESS) {
            revert ();
        }
    }

    function createTokenWithCustomFees(IHederaTokenService.TokenKey[] memory keys,
        IHederaTokenService.FixedFee[] memory fixedFees, IHederaTokenService.FractionalFee[] memory fractionalFees, address treasury) internal returns (address createdTokenAddress) {
        IHederaTokenService.HederaToken memory token;
        token.name = name;
        token.symbol = symbol;
        token.treasury = treasury;
        token.tokenKeys = keys;

        (int responseCode, address tokenAddress) =
        HederaTokenService.createFungibleTokenWithCustomFees(token, initialTotalSupply, decimals,
            fixedFees,
            fractionalFees);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ();
        }

        createdTokenAddress = tokenAddress;
    }
}

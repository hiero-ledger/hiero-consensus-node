// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./FeeHelper.sol";
import "./HederaResponseCodes.sol";
import "./HederaTokenService.sol";


contract UpdateTokenFeeSchedules is HederaTokenService, FeeHelper {

    function updateFungibleFixedHbarFee(address tokenAddress, int64 amount, address collector) external returns(int64 responseCode){

        IHederaTokenService.FixedFee[] memory fixedFees = new IHederaTokenService.FixedFee[](1);
        IHederaTokenService.FixedFee memory fixedHbarFee = createFixedHbarFee(amount, collector);
        fixedFees[0] = fixedHbarFee;
        IHederaTokenService.FractionalFee[] memory fractionalFees = new IHederaTokenService.FractionalFee[](0);
        responseCode = updateFungibleTokenCustomFees(tokenAddress, fixedFees, fractionalFees);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of custom fee failed!");
        }
        return responseCode;
    }

    function updateFungibleFees(address tokenAddress, int64 amount, address feeToken, int64 numerator, int64 denominator, bool netOfTransfers, address collector) external returns(int64 responseCode){

        IHederaTokenService.FixedFee[] memory fixedFees = createFixedFeesWithAllTypes(amount, feeToken, collector);

        IHederaTokenService.FractionalFee[] memory fractionalFees = new IHederaTokenService.FractionalFee[](1);
        IHederaTokenService.FractionalFee memory fractionalFee = createFractionalFee(numerator, denominator, netOfTransfers, collector);
        fractionalFees[0] = fractionalFee;

        responseCode = updateFungibleTokenCustomFees(tokenAddress, fixedFees, fractionalFees);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of custom fee failed!");
        }
        return responseCode;
    }

    function updateFungibleFixedHbarFees(address tokenAddress, uint8 numberOfFees, int64 amount, address collector) external returns(int64 responseCode) {

        IHederaTokenService.FixedFee[] memory fixedFees = createNAmountFixedFeesForHbars(numberOfFees, amount, collector);
        IHederaTokenService.FractionalFee[] memory fractionalFees = new IHederaTokenService.FractionalFee[](0);
        responseCode = updateFungibleTokenCustomFees(tokenAddress, fixedFees, fractionalFees);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of custom fee failed!");
        }
        return responseCode;
    }

    function updateNonFungibleFixedHbarFee(address tokenAddress, int64 amount, address collector) external returns(int64 responseCode) {

        IHederaTokenService.FixedFee[] memory fixedFees = new IHederaTokenService.FixedFee[](1);
        IHederaTokenService.FixedFee memory fixedHbarFee = createFixedHbarFee(amount, collector);
        fixedFees[0] = fixedHbarFee;
        IHederaTokenService.RoyaltyFee[] memory royaltyFees = new IHederaTokenService.RoyaltyFee[](0);
        responseCode = updateNonFungibleTokenCustomFees(tokenAddress, fixedFees, royaltyFees);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of custom fee failed!");
        }
        return responseCode;
    }

    function updateFungibleFixedHtsFee(address tokenAddress, address feeToken, int64 amount, address collector) external returns(int64 responseCode){

        IHederaTokenService.FixedFee[] memory fixedFees = new IHederaTokenService.FixedFee[](1);
        IHederaTokenService.FixedFee memory fixedHtsFee = createFixedTokenFee(amount, feeToken, collector);
        fixedFees[0] = fixedHtsFee;
        IHederaTokenService.FractionalFee[] memory fractionalFees = new IHederaTokenService.FractionalFee[](0);
        int responseCode = updateFungibleTokenCustomFees(tokenAddress, fixedFees, fractionalFees);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of custom fee failed!");
        }
    }

    function updateNonFungibleFixedHtsFee(address tokenAddress, address feeToken, int64 amount, address collector) external returns(int64 responseCode){

        IHederaTokenService.FixedFee[] memory fixedFees = new IHederaTokenService.FixedFee[](1);
        IHederaTokenService.FixedFee memory fixedHtsFee = createFixedTokenFee(amount, feeToken, collector);
        fixedFees[0] = fixedHtsFee;
        IHederaTokenService.RoyaltyFee[] memory royaltyFees = new IHederaTokenService.RoyaltyFee[](0);
        responseCode = updateNonFungibleTokenCustomFees(tokenAddress, fixedFees, royaltyFees);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of custom fee failed!");
        }
        return responseCode;
    }

    function updateNonFungibleFees(address tokenAddress, address feeToken, int64 amount, int64 numerator, int64 denominator, address collector) external returns(int64 responseCode){

        IHederaTokenService.FixedFee[] memory fixedFees = new IHederaTokenService.FixedFee[](1);
        IHederaTokenService.FixedFee memory fixedHtsFee = createFixedTokenFee(amount, feeToken, collector);
        fixedFees[0] = fixedHtsFee;
        IHederaTokenService.RoyaltyFee[] memory royaltyFees = createRoyaltyFeesWithAllTypes(numerator, denominator, amount, feeToken, collector);

        responseCode = updateNonFungibleTokenCustomFees(tokenAddress, fixedFees, royaltyFees);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of custom fee failed!");
        }
        return responseCode;
    }

    function updateFungibleFixedTokenFee(address tokenAddress, int64 amount, address collector) external returns(int64 responseCode){

        IHederaTokenService.FixedFee[] memory fixedFees = new IHederaTokenService.FixedFee[](1);
        IHederaTokenService.FixedFee memory fixedHtsFee = createFixedFeeForCurrentToken(amount, collector);
        fixedFees[0] = fixedHtsFee;
        IHederaTokenService.FractionalFee[] memory fractionalFees = new IHederaTokenService.FractionalFee[](0);
        responseCode = updateFungibleTokenCustomFees(tokenAddress, fixedFees, fractionalFees);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of custom fee failed!");
        }
        return responseCode;
    }

    function updateNonFungibleFixedTokenFee(address tokenAddress, int64 amount, address collector) external returns(int64 responseCode){

        IHederaTokenService.FixedFee[] memory fixedFees = new IHederaTokenService.FixedFee[](1);
        IHederaTokenService.FixedFee memory fixedHtsFee = createFixedFeeForCurrentToken(amount, collector);
        fixedFees[0] = fixedHtsFee;
        IHederaTokenService.RoyaltyFee[] memory royaltyFees = new IHederaTokenService.RoyaltyFee[](0);
        responseCode = updateNonFungibleTokenCustomFees(tokenAddress, fixedFees, royaltyFees);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of custom fee failed!");
        }
        return responseCode;
    }

    function updateFungibleFractionalFee(address tokenAddress, int64 numerator, int64 denominator, bool netOfTransfers, address collector) external returns(int64 responseCode){

        IHederaTokenService.FixedFee[] memory fixedFees = new IHederaTokenService.FixedFee[](0);
        IHederaTokenService.FractionalFee[] memory fractionalFees = new IHederaTokenService.FractionalFee[](1);
        IHederaTokenService.FractionalFee memory fractionalFee = createFractionalFee(numerator, denominator, netOfTransfers, collector);
        fractionalFees[0] = fractionalFee;

        responseCode = updateFungibleTokenCustomFees(tokenAddress, fixedFees, fractionalFees);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of custom fee failed!");
        }
        return responseCode;
    }

    function updateFungibleFractionalFees(address tokenAddress, uint8 numberOfFees, int64 numerator, int64 denominator, bool netOfTransfers, address collector) external returns(int64 responseCode){

        IHederaTokenService.FixedFee[] memory fixedFees = new IHederaTokenService.FixedFee[](0);
        IHederaTokenService.FractionalFee[] memory fractionalFees = createNAmountFractionalFees(numberOfFees, numerator, denominator, netOfTransfers, collector);

        responseCode = updateFungibleTokenCustomFees(tokenAddress, fixedFees, fractionalFees);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of custom fee failed!");
        }
        return responseCode;
    }

    function updateFungibleFractionalFeeMinAndMax(address tokenAddress, int64 numerator, int64 denominator, int64 minimumAmount, int64 maximumAmount, bool netOfTransfers, address collector) external returns(int64 responseCode){

        IHederaTokenService.FixedFee[] memory fixedFees = new IHederaTokenService.FixedFee[](0);
        IHederaTokenService.FractionalFee[] memory fractionalFees = new IHederaTokenService.FractionalFee[](1);
        IHederaTokenService.FractionalFee memory fractionalFee = createFractionalFeeWithMinAndMax(numerator, denominator, minimumAmount, maximumAmount, netOfTransfers, collector);
        fractionalFees[0] = fractionalFee;

        responseCode = updateFungibleTokenCustomFees(tokenAddress, fixedFees, fractionalFees);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of custom fee failed!");
        }
        return responseCode;
    }

    function updateNonFungibleRoyaltyFee(address tokenAddress, int64 numerator, int64 denominator, address collector) external returns(int64 responseCode){

        IHederaTokenService.FixedFee[] memory fixedFees = new IHederaTokenService.FixedFee[](0);

        IHederaTokenService.RoyaltyFee[] memory royaltyFees = new IHederaTokenService.RoyaltyFee[](1);
        IHederaTokenService.RoyaltyFee memory royaltyFee = createRoyaltyFeeWithoutFallback(numerator, denominator, collector);
        royaltyFees[0] = royaltyFee;

        responseCode = updateNonFungibleTokenCustomFees(tokenAddress, fixedFees, royaltyFees);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of custom fee failed!");
        }
        return responseCode;
    }

    function updateNonFungibleRoyaltyFees(address tokenAddress, uint8 numberOfFees, int64 numerator, int64 denominator, address collector) external returns(int64 responseCode){

        IHederaTokenService.FixedFee[] memory fixedFees = new IHederaTokenService.FixedFee[](0);

        IHederaTokenService.RoyaltyFee[] memory royaltyFees = createNAmountRoyaltyFees(numberOfFees, numerator, denominator, collector);

        int responseCode = updateNonFungibleTokenCustomFees(tokenAddress, fixedFees, royaltyFees);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of custom fee failed!");
        }
    }

    function updateNonFungibleRoyaltyFeeHbarFallback(address tokenAddress, int64 numerator, int64 denominator, int64 amount, address collector) external returns(int64 responseCode){

        IHederaTokenService.FixedFee[] memory fixedFees = new IHederaTokenService.FixedFee[](0);

        IHederaTokenService.RoyaltyFee[] memory royaltyFees = new IHederaTokenService.RoyaltyFee[](1);
        IHederaTokenService.RoyaltyFee memory royaltyFee = createRoyaltyFeeWithHbarFallbackFee(numerator, denominator, amount, collector);
        royaltyFees[0] = royaltyFee;

        responseCode = updateNonFungibleTokenCustomFees(tokenAddress, fixedFees, royaltyFees);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of custom fee failed!");
        }
        return responseCode;
    }

    function updateNonFungibleRoyaltyFeeHtsFallback(address tokenAddress, address feeToken, int64 numerator, int64 denominator, int64 amount, address collector) external returns(int64 responseCode){

        IHederaTokenService.FixedFee[] memory fixedFees = new IHederaTokenService.FixedFee[](0);

        IHederaTokenService.RoyaltyFee[] memory royaltyFees = new IHederaTokenService.RoyaltyFee[](1);
        IHederaTokenService.RoyaltyFee memory royaltyFee = createRoyaltyFeeWithTokenDenominatedFallbackFee(numerator, denominator, amount, feeToken, collector);
        royaltyFees[0] = royaltyFee;

        int responseCode = updateNonFungibleTokenCustomFees(tokenAddress, fixedFees, royaltyFees);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of custom fee failed!");
        }
    }

    function resetFungibleTokenFees(address tokenAddress) external returns(int64 responseCode){
        IHederaTokenService.FixedFee[] memory fixedFees = new IHederaTokenService.FixedFee[](0);
        IHederaTokenService.FractionalFee[] memory fractionalFees = new IHederaTokenService.FractionalFee[](0);
        responseCode = updateFungibleTokenCustomFees(tokenAddress, fixedFees, fractionalFees);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of custom fee failed!");
        }
        return responseCode;
    }


    function resetNonFungibleTokenFees(address tokenAddress) external returns(int64 responseCode) {
        IHederaTokenService.FixedFee[] memory fixedFees = new IHederaTokenService.FixedFee[](0);
        IHederaTokenService.RoyaltyFee[] memory royaltyFees = new IHederaTokenService.RoyaltyFee[](0);
        responseCode = updateNonFungibleTokenCustomFees(tokenAddress, fixedFees, royaltyFees);

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Update of custom fee failed!");
        }
        return responseCode;
    }
}

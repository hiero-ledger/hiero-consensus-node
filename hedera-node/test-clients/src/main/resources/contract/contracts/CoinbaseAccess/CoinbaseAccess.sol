// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.24;

// Compiled with solc 0.8.24 and --evm-version paris (no PUSH0), so it runs on every supported EVM version
contract CoinbaseAccess {
    // Each function returns the gas spent by one access to block.coinbase and by the same access to this
    // contract, whose address is always warm; both are measured by the same instruction sequence
    function callCosts() external returns (uint256 coinbaseCost, uint256 selfCost) {
        coinbaseCost = callCost(block.coinbase);
        selfCost = callCost(address(this));
    }

    function balanceCosts() external view returns (uint256 coinbaseCost, uint256 selfCost) {
        coinbaseCost = balanceCost(block.coinbase);
        selfCost = balanceCost(address(this));
    }

    function extCodeSizeCosts() external view returns (uint256 coinbaseCost, uint256 selfCost) {
        coinbaseCost = extCodeSizeCost(block.coinbase);
        selfCost = extCodeSizeCost(address(this));
    }

    function extCodeHashCosts() external view returns (uint256 coinbaseCost, uint256 selfCost) {
        coinbaseCost = extCodeHashCost(block.coinbase);
        selfCost = extCodeHashCost(address(this));
    }

    function extCodeCopyCosts() external view returns (uint256 coinbaseCost, uint256 selfCost) {
        coinbaseCost = extCodeCopyCost(block.coinbase);
        selfCost = extCodeCopyCost(address(this));
    }

    function callCost(address target) private returns (uint256 cost) {
        assembly {
            let before := gas()
            pop(call(0, target, 0, 0, 0, 0, 0))
            cost := sub(before, gas())
        }
    }

    function balanceCost(address target) private view returns (uint256 cost) {
        assembly {
            let before := gas()
            mstore(0, balance(target))
            cost := sub(before, gas())
        }
    }

    function extCodeSizeCost(address target) private view returns (uint256 cost) {
        assembly {
            let before := gas()
            mstore(0, extcodesize(target))
            cost := sub(before, gas())
        }
    }

    function extCodeHashCost(address target) private view returns (uint256 cost) {
        assembly {
            let before := gas()
            mstore(0, extcodehash(target))
            cost := sub(before, gas())
        }
    }

    function extCodeCopyCost(address target) private view returns (uint256 cost) {
        assembly {
            let before := gas()
            extcodecopy(target, 0, 0, 0)
            cost := sub(before, gas())
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.24;

// Compiled with solc 0.8.24 and --evm-version paris (no PUSH0), so it runs on every supported EVM version
contract RepeatedAccess {
    // Each function returns the gas spent by two consecutive accesses to the given address, both measured
    // by the same instruction sequence; the first access warms the address for the second one (EIP-2929)
    function callCosts(address target) external returns (uint256 firstCost, uint256 secondCost) {
        firstCost = callCost(target);
        secondCost = callCost(target);
    }

    function callCodeCosts(address target) external returns (uint256 firstCost, uint256 secondCost) {
        firstCost = callCodeCost(target);
        secondCost = callCodeCost(target);
    }

    function delegateCallCosts(address target) external returns (uint256 firstCost, uint256 secondCost) {
        firstCost = delegateCallCost(target);
        secondCost = delegateCallCost(target);
    }

    function staticCallCosts(address target) external view returns (uint256 firstCost, uint256 secondCost) {
        firstCost = staticCallCost(target);
        secondCost = staticCallCost(target);
    }

    function balanceCosts(address target) external view returns (uint256 firstCost, uint256 secondCost) {
        firstCost = balanceCost(target);
        secondCost = balanceCost(target);
    }

    function extCodeSizeCosts(address target) external view returns (uint256 firstCost, uint256 secondCost) {
        firstCost = extCodeSizeCost(target);
        secondCost = extCodeSizeCost(target);
    }

    function extCodeHashCosts(address target) external view returns (uint256 firstCost, uint256 secondCost) {
        firstCost = extCodeHashCost(target);
        secondCost = extCodeHashCost(target);
    }

    function extCodeCopyCosts(address target) external view returns (uint256 firstCost, uint256 secondCost) {
        firstCost = extCodeCopyCost(target);
        secondCost = extCodeCopyCost(target);
    }

    function callCost(address target) private returns (uint256 cost) {
        assembly {
            let before := gas()
            pop(call(0, target, 0, 0, 0, 0, 0))
            cost := sub(before, gas())
        }
    }

    function callCodeCost(address target) private returns (uint256 cost) {
        assembly {
            let before := gas()
            pop(callcode(0, target, 0, 0, 0, 0, 0))
            cost := sub(before, gas())
        }
    }

    function delegateCallCost(address target) private returns (uint256 cost) {
        assembly {
            let before := gas()
            pop(delegatecall(0, target, 0, 0, 0, 0))
            cost := sub(before, gas())
        }
    }

    function staticCallCost(address target) private view returns (uint256 cost) {
        assembly {
            let before := gas()
            pop(staticcall(0, target, 0, 0, 0, 0))
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

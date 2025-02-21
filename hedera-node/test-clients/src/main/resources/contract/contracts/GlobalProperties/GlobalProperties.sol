// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

contract GlobalProperties {

    function getChainID() external view returns (uint256) {
        return block.chainid;
    }

    function getGasLimit() external view returns (uint256) {
        return block.gaslimit;
    }

    function getBaseFee() external view returns (uint256) {
        return block.basefee;
    }

    function getCoinbase() external view returns (address) {
        return block.coinbase;
    }

}

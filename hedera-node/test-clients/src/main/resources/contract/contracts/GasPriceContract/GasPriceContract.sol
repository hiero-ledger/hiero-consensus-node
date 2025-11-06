// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.10;

contract GasPriceContract {

    uint256 public lastTxGasPrice;

    function updateGasPrice() public {
        // tx.gasprice returns the gas price (in Wei) of the transaction that is currently executing.
        lastTxGasPrice = tx.gasprice;
    }

    function getLastTxGasPrice() public view returns (uint256) {
        return lastTxGasPrice;
    }

    function getTxGasPrice() public view returns (uint256) {
        return tx.gasprice;
    }
}

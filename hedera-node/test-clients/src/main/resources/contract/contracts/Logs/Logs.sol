// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.0;

contract Logs {

    event Log0(uint256 num1) anonymous; // Does not include topic
    event Log1(uint256 indexed num0);
    event Log2(uint256 indexed num0, uint256 indexed num1);
    event Log3(uint256 indexed num0, uint256 indexed num1, uint256 indexed num2);
    event Log4(uint256 indexed num0, uint256 indexed num1, uint256 indexed num2, uint256 num3);

    function log0(uint n) public {
        emit Log0(n);
    }

    function log1(uint n) public {
        emit Log1(n);
    }

    function log2(uint n0, uint n1) public {
        emit Log2(n0, n1);
    }

    function log3(uint n0, uint n1, uint n2) public {
        emit Log3(n0, n1, n2);
    }

    function log4(uint n0, uint n1, uint n2, uint n3) public {
        emit Log4(n0, n1, n2, n3);
    }
}

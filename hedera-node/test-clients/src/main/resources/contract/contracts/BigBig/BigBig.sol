// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.5.3;

contract BigBig {
  uint32 luckyNumber = 42;

  function pick(uint32 how) public pure returns (bytes memory) {
    return new bytes(how);
  }
}

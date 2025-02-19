// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.9;

contract RevertingSendTry {
  event ErrorLog(string reason);

  constructor() payable {}  

  function sendTo(uint32 amount, uint32 numA, uint32 numB) public payable {
    address payable aBeneficiary = payable(address(uint160(numA)));
    aBeneficiary.transfer(amount);

    /* Use this. qualifier to force external call */
    try this.revertAfterSending(amount, numA, numB) {
      /* Can't get here */
    } catch Error(string memory reason) {
      emit ErrorLog(reason);
    }

    /* We expect the balance changes: 
     *   0.0.numA -> +amount
     *   0.0.numB -> 0
     */
  }

  function revertAfterSending(uint32 amount, uint32 numA, uint32 numB) public payable {
    address payable aBeneficiary = payable(address(uint160(numA)));
    aBeneficiary.transfer(amount);
    address payable bBeneficiary = payable(address(uint160(numB)));
    bBeneficiary.transfer(amount);

    require(0 == 1, "Changed my mind!");
  }
}

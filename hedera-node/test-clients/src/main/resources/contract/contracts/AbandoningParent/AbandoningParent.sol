// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.5.0;

contract Child {
}

contract AbandoningParent {
    constructor() public {
      Child one = new Child();
      Child two = new Child();
      Child three = new Child();
      Child four = new Child();
      Child five = new Child();
    }
}


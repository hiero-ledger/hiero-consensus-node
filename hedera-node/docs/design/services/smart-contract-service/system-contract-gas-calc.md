# Hedera Gas Fee Calculation

Gas is used to charge fees to pay for work performed by the network when a smart contract transaction is submitted.
Specifically, transactions of type, `ContractCall`, `ContractCreate` and `EthereumTransactions` have fees charged
denominated in gas.  Other smart contracts related transactions `ContractDelete`, `ContractGetInfo` etc. are only
accessed the normal Hedera related network, node and service fees denominated in Hbars.

## Intrinsic Gas

A transaction submitted to the smart contract service must be sent with enough gas to cover intrinsic gas.  With the Shanghai fork of the EVM update, intrinsic gas is calculated as:

```jsx
21000
+ 4 * number of zeros bytes in transaction payload
+ 16 * number of non-zeros bytes in transaction payload
```

If insufficient gas is submitted, the transaction will fail during precheck and no record will be created.

## Processing EVM Op Codes

In general the gas charges for processing native EVM op codes (and precompiles) are the same as defined by the Ethereum network and are summarized in chart form in this GitHub project.

## System Contracts

As there are no corresponding functionality in the EVM for Hedera specific system contract functions, a method for reasonably determining and calculating the gas charge for performing these operations needs to be defined.  In general, system contracts expose HAPI functionality and thus a natural way to determine the gas charge is as follows:

1. Take the canonical price for the HAPI operation performed by the function.  This defines a *minimum price* denominated in tinycents.
2. Calculate a *nominal price* for the synthetic transaction body to perform the function using the fee calculator in tinybars.
3. Convert the *nominal price* from step 2 into tinycents.
4. Take the maximum of the *minimum price* and the *nominal price* to calculate a *final price* in tinycents.
5. Convert the final price into gas by utilizing the resource price calculator which provides a conversion factor from Hbar to gas (currently 852_000).
6. Finally, apply a 20% markup to the final gas amount to account for system contract overhead.

### Example

1. As an example a token mint function has a canonical price of $0.001.  Multiplying this value by 100 cents/dollar * 100_000_000 tinycents/cents = 10_000_000 tinycents.  This is the *minimal price* for a mint transaction.
2. The *nominal pric*e is calculated by passing the synthetic transaction that is dispatched to the token service into the fee calculator and uses the total of the network, node and services fees using the prices defined in the `feeSchedule.json` file. The nominal price given the feeSchedule.json in the test environment gives us a value of 281_817 tinybars.
3. Convert the *nominal price* calculated in step 2 by the exchange rate (in the test environment the exchange rate is 12 tinycents to tinybar) by multiplying by the exchange rate.  281817 * 12 = 3_381_804 tinycents
4. Take the maximum value of the *minimum price* (10_000_000) and the *nominal price* (3_381_804) = 10_000_000 tinycents.
5. Convert value from 4 into gas by round up and multiplying gas a fee schedule units per tinycents conversion factor (1000).
   1. (10_000_000 + 852000 - 1) * 1000 / 852_000 = 12737 gas
6. Apply a 20% markup
   1. 12737 + ( 12737 / 5 ) = 15284 gas

The method described above is used by the follow system contract functions for determining gas charges.

|     **System Contract Function**      |     **Canonical Price ($)**     | System Contract |
|---------------------------------------|---------------------------------|-----------------|
| hbarApprove                           | 0.05                            | HAS             |
| associate                             | 0.05                            | HTS             |
| dissociate                            | 0.05                            | HTS             |
| burnToken                             | 0.001                           | HTS             |
| createFungibleToken*                  | 1.00                            | HTS             |
| createNonFungibleToken*               | 1.00                            | HTS             |
| createFungibleTokenWithCustomFees*    | 2.00                            | HTS             |
| createNonFungibleTokenWithCustomFees* | 2.00                            | HTS             |
| deleteToken                           | 0.001                           | HTS             |
| freezeToken                           | 0.001                           | HTS             |
| unfreezeToken                         | 0.001                           | HTS             |
| approve                               | 0.05                            | HTS             |
| grantTokenKyc                         | 0.001                           | HTS             |
| revokeTokenKyc                        | 0.001                           | HTS             |
| mintToken (Fungible)                  | 0.001                           | HTS             |
| mintToken (Non-fungible)              | 0.02                            | HTS             |
| pauseToken                            | 0.001                           | HTS             |
| unpauseToken                          | 0.001                           | HTS             |
| cryptoTransfer                        | 0.001 per FT plus 0.002 per NFT | HTS             |
| transferToken                         | 0.001                           | HTS             |
| transferTokens                        | 0.001 per token                 | HTS             |
| transferNFT                           | 0.002                           | HTS             |
| transferNFTs                          | 0.002 per token                 | HTS             |
| updateTokenInfo                       | 0.001                           | HTS             |
| wipeTokenAccount                      | 0.001                           | HTS             |
| wipeTokenAccountNFT                   | 0.001                           | HTS             |

- Note: There is an additional charge in addition to gas fees (paid in hbars) for creating the tokens.
  This needs to be passed into the contract creating the token as `{msg.value}`

## System Contract View Functions

The gas requirements for HTS view functions can be calculated in a slightly modified manner.  The transaction type of `getTokenInfo` can be used and a nominal price need not be calculated.  This implies that converting the fee into Hbars is not necessary as the canonical price ($0.0001) can be directly converted into gas by using the conversion factor of 852 tinycents.  Thus gas cost is:

Base gas cost = (1_000_000 + 852000 - 1) * 1000 / 852_000 = 2173

Add 20% markup = 2173 + (2173 / 5) = 2607 gas

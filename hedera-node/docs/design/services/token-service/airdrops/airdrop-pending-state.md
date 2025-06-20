# Pending state for airdrops

## Purpose

In order for the `TokenAirdropTransaction` to work we should define a new section of state, where airdrops to be stored. It should be a special storage of incoming airdrop information, that could potentially be used to build `CryptoTransfer` transactions upon it.

The additional new `TokenClaimAirdrop` and `TokenCancelAirdrop` transactions would operate tightly with the pending state. These operations would trigger a removal of entities from the pending state. The `claim` operation would create a synthetic crypto transfer operation and remove the airdrop information, that is being claimed from the pending state and the `cancel` would directly remove the airdrop from the pending state.

## Prerequisite reading

* [HIP-904](https://hips.hedera.com/hip/hip-904)

## Goals

1. Define state structure where to keep the information for incoming airdrops, so that they could be canceled or claimed
2. Define what state transitions inside the pending state would be allowed in the scope of airdrop operations and how changes would be processed
3. Describe how this new state would be serialized and kept after restarts
4. Define Readable and Writable stores for accessing and manipulating the pending airdrops state

## Architecture

The pending airdrop state would be part of the token state, since the airdrops are tightly connected to tokens and we can keep them at the same place. The pending nature of this state can be described as keeping incoming airdrops, without directly updating the sender and the receiver balances as in a regular transfer. This is needed to avoid transferring spam tokens and the receiver to retain full control of which airdrop to claim or ignore. Canceling an airdrop could be performed only from sender's side.

New airdrop specific operations are also introduced - those are `TokenAirdrop`, `TokenClaimAirdrop` and `TokenCancelAirdrop`.

Details for all of them would be covered in their own design docs, but here is a general overview of how they would interact with the pending state:
- `TokenAirdrop` would add a new airdrop to the pending state
- `TokenClaimAirdrop` would have a handle logic, which would take and remove a pending airdrop from this state, create a synthetic `CryptoTransfer` and execute it
- `TokenCancelAirdrop` operation would remove an existing airdrop from the state

### Structure

We should define the structure of pending changes that would be kept in the `InitialModServiceTokenSchema` state, so we would reuse the token’s serialized state and add pending airdrops records in it. We could use the type defined in the protobuf records for the airdrop information itself. We should use the protobuf types since they are used for state serialization. So, the structure could look like this:

`StateDefinition.*onDisk*(*AIRDROPS_KEY*, PendingAirdropId.PROTOBUF, PendingAirdropValue.PROTOBUF, MAX__AIRDROPS);`

We can have a case, where we have multiple airdrops for the same fungible token. In this case the  `PendingAirdropValue` would be updated with the new aggregated value.

### Models

In order to be able to iterate over all pending airdrops for a specific sender account, we should enrich the account protobuf with 1 new field:

```proto
message Account {
    ...
    PendingAirdropId head_pendingAirdropId;
    ...
}
```

In addition, we should define a new protobuf type that will be used for the construction of a linked list holding the sender's pending airdrops:

```proto
message AccountAirdrop {
    /**
     * The airdrop involved in the relation between a sender account and the airdrop initiated by them
     */
    PendingAirdropId pending_airdrop_id;

    /**
     * The previous airdrop id of sender account's airdrops linked list
     */
    PendingAirdropId previous_airdrop;

    /**
     * The next airdrop id of sender account's airdrops linked list
     */
    PendingAirdropId next_airdrop;
}
```

The traversing of all relevant airdrops for an account will be needed when for an example a sender account is expired or deleted and we need to cancel all of their pending airdrops beforehand.

### Stores

We need to define a special `WritableStore` for the airdrops, so that we can manipulate the content in them. This would be the `WritableAirdropStore`.

It would extend `ReadableAirdropStoreImpl` that implements the following interface:

```java
public interface ReadableAirdropStore {
   /**
    * Returns the {@link PendingAirdropValue} with the given ID. If no such airdrop exists,
    * returns {@code null}
    *
    * @param tokenAirdropId - the id of the airdrop
    * @return the airdrop value in case of fungible token
    */
   @Nullable
   PendingAirdropValue get(@NonNull final PendingAirdropId tokenAirdropId);

   /**
    * Returns whether a given PendingAirdropId exists in state.
    *
    * @param tokenAirdropId - the id of the airdrop
    * @return true if the airdrop exists, false otherwise
    */
   boolean exists(@NonNull final PendingAirdropId tokenAirdropId);

   /**
    * Returns the number of airdrops in the state.
    * @return the number of airdrops in the state.
    */
   long sizeOfState();

   /**
    * Warms the system by preloading an airdrop into memory
    *
    * <p>The default implementation is empty because preloading data into memory is only used for some implementations.
    *
    * @param {@link PendingAirdropId} the airdrop id
    */
   void warm(@NonNull final PendingAirdropId airdropId);
}
```

The `ReadableAirdropStoreImpl` would keep an instance of `ReadableKVState<PendingAirdropId, PendingAirdropValue> readableAirdropState`.

The `WritableAirdropStore` would have the following methods:

```java
public class WritableAirdropStore {

   /**
    * Persists a new {@link PendingAirdropValue} into the state
    *
    * @param airdropId - the airdropId to be persisted
    * @param airdropValue - the airdropValue to be persisted
    */
   void put(@NonNull final PendingAirdropId airdropId, @NonNull final PendingAirdropValue airdropValue) {}

   /**
    * Removes a {@link PendingAirdropId} from the state
    *
    * @param airdropId the {@code PendingAirdropId} to be removed
    */
   void remove(@NonNull PendingAirdropId airdropId) {}
}
```

The `WritableAirdropStoreImpl` would keep an instance of `WritableKVState<PendingAirdropId, PendingAirdropValue> airdropState`.

### Operations

1. Fetching airdrop values from state for reading or modification. This would be applicable only for fungible airdrops (for both `TokenAirdrop` and `TokenClaimAirdrop` transactions).
   For a specific `PendingAirdropId`, we would get the corresponding `PendingAirdropValue`. For NFT airdrops we don't have a corresponding `PendingAirdropValue` and the information for them would be encapsulated in the `PendingAirdropId` itself.
2. Fetching all PendingAirdropIds, so that we can iterate over them and perform business logic (e.g. in case of sender or token expirations)
3. Handling a new airdrop in state. We can have several cases:
   - Only a single fungible or non-fungible airdrop for a specific token defined - we just put a new entry in the map
   - An airdrop for an existing `PendingAirdropId` is already defined - we should get the existing `PendingAirdropValue` for this `PendingAirdropId` and modify it with the aggregated new fungible amount
4. Handling an airdrop `cancel` in state - we remove the `PendingAirdropId` key for the airdrop we want to cancel
5. Handling an airdrop `claim` in state - we remove the `PendingAirdropId` key for the airdrop we want to claim

## Acceptance tests

The airdrop state correctness would be validated by acceptance tests performed by the `TokenAirdropTransaction`, `TokenCancelAirdrop` and `TokenClaimAirdrop`.

The airdrop state itself could be covered by a set of unit tests, covering different corner cases, as well as positive and negative scenarios.

The main behaviour that should be validated for the airdrop state and its storage is the following:

Note: For all cases the recipient account shouldn’t be associated with the airdropped token.

### State management on airdrop (populate pending airdrop state only)

1. Airdrop a fungible token with a negative amount to Alice → the airdrop should fail and nothing should be saved in the pending state
2. Airdrop a fungible token with a `Long.MAX_VALUE` amount to Alice and then make a second airdrop with the same fungible token and amount with a value of 1 again to Alice → the second airdrop attempt should fail and it’s data shouldn’t be saved in the pending state
3. Airdrop an NFT with a negative serial number to Alice → the airdrop should fail and nothing should be saved in the pending state
4. Airdrop an NFT with a serial number 1 to Alice and then perform another airdrop with the same token and a serial number 2 again to Alice → the airdrop should succeed and all of the airdrop data should be saved in the pending state
5. Airdrop a fungible token with amount X to Alice and the same fungible token with amount Y to Bob → the airdrops should succeed and all airdrop data should be saved in the pending state
6. Airdrop a fungible token with amount X to Alice and the same fungible token with amount Y again to Alice → the airdrops should succeed and the airdrop data should be aggregated having an amount of X + Y and should be saved in the pending state

### State management on cancel (erase pending airdrop state only)

1. Cancel a fungible token airdrop with token X and amount Y to Alice → the state shouldn’t keep the airdrop for Bob with token X at all
2. Cancel an NFT airdrop with token X and serial number 1 to Alice →  the state shouldn’t keep the airdrop for Bob with token X and serial number 1
3. Airdrop an NFT with tokenId X and serial numbers 1 and 2 to Alice. Cancel the airdrop with NFT X and serial number 1 →  the state shouldn’t keep the airdrop for Alice with NFT X and serial number 1 but should continue to keep the airdrop for Alice with NFT X and serial number 2
4. Airdrop a fungible token X with amount Y to Alice and an NFT Z and serial number 1 again to Alice. Cancel the airdrop for fungible token X and for NFT Z with serial number 1 → pending state shouldn’t keep the airdrops for Alice
5. Airdrop a fungible token X with amount Y to Alice and an NFT Z and serial number 1 to Bob. Cancel the airdrop for fungible token X to Alice and for NFT Z and serial number 1 to Bob → pending state shouldn’t keep the airdrops for Alice and Bob
6. Airdrop a fungible token X with amount Y to Alice, an NFT Z and serial number 1 to Bob and the same NFT with serial number 2 to Carol. Cancel the airdrop for NFT Z and serial number 2 to Carol → pending state shouldn’t keep the airdrop to Carol, but should keep the airdrops to Alice and Bob

### State management on claim (erase pending airdrop state only after successful claim)

1. Claim an airdrop with a fungible token X with amount Y airdropped from Alice to Bob
2. Claim an airdrop with an NFT with token X and a serial number 1 airdropped from Alice to Bob
3. Claim multiple fungible airdrops - a fungible token X with amount Y airdropped from Alice to Bob and a fungible token Z with amount Y from Carol to Bob
4. Claim multiple nft airdrops with the same sender and TokenId but different serial numbers - an NFT X with serial numbers 1 and 2 airdropped to Bob
5. Claim multiple nft airdrops with the same TokenId but different senders and serial numbers - NFT X with serial number 1 airdropped from Alice to Bob and NFT X with serial number 2 airdropped from Carol to Bob
6. Claim multiple nft airdrops with different TokenIds but same sender and serial numbers - NFT X with serial number 1 airdropped from Alice to Bob and NFT Y with serial number 1 airdropped from Alice to Bob
7. Claim multiple nft airdrops with different TokenIds, different serial numbers but same sender - NFT X with serial number 1 airdropped from Alice to Bob and NFT Y with serial number 2 airdropped from Alice to Bob
8. Claim multiple nft airdrops with different TokenIds, different serial numbers and different senders - NFT X with serial number 1 airdropped from Alice to Bob, NFT Y with serial number 2 airdropped from Carol to Bob

For all cases the pending state shouldn't keep the airdrops for Bob, if they are claimed successfully.

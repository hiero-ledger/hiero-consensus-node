# Intsructions for setting up two local, independent Hiero 'ledgers'

1. Clone the `hiero-consensus-node` repository from [GitHub](https://github.com/hiero-ledger/hiero-consensus-node) 
```
git clone https://github.com/hiero-ledger/hiero-consensus-node.git
```

2. Copy the `hiero-consensus-node` directory (with a new name, e.g. `hiero-consensus-node-2`). 
There should be two identical (but differently-named) directories, each with a separate copy of the
consensus node codebase.

4. In _one_ of the directories, apply the `application.properties` patch changes from [this file](../configuration/dev/clpr-application.properties.patch).
The complete changeset should look similar to this:
```diff
#contracts.chainId=298                  # Disable and redefine below
#ledger.id=0x03                         # Disable and redefine below
#grpc.nodeOperatorPortEnabled=true      # Disable and redefine below

contracts.chainId=299
ledger.id=0x04
grpc.nodeOperatorPortEnabled=false
grpc.port=51211
grpc.tlsPort=51212
```

5. Build the project (separately) in both directories:
```
# Build the first instance:
cd hiero-consensus-node-1       # root of the first instance
./gradlew clean assemble

# Build the second instance:
cd ../hiero-consensus-node-2    # root of the second instance
# (same command as above)
```

5. Start both instances (tip: use separate terminal windows for each)
```
./gradlew :app:run              # Run from the root of the instance (e.g. `hiero-consensus-node-1`)
```

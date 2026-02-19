# One-line launch & go & debug
# Nuked when run, so must be copied every time
cp configuration/dev/genesis-network.json data/config
# Cleanup from prior run
rm -rf data/saved data/stats data/tmp data/*treams data/accountBalances output .archive /opt/hgcapp/*
# Launch and go
UseBonnevilleEVM=1 java -cp '.:data/lib/*:data/apps/*' -Dflag=1 com.hedera.node.app.ServicesMain -local 0

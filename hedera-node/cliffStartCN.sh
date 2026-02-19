# One-line launch & go & debug
#cp configuration/dev/config.txt .; rm -rf data/saved data/stats data/tmp data/*treams data/accountBalances output .archive /opt/hgcapp/*; UseBonnevilleEVM=1 java -cp '.:data/lib/*:data/apps/*' -Dflag=1 '-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5006' com.hedera.node.app.ServicesMain -local 0

cp configuration/dev/config.txt .
rm -rf data/saved data/stats data/tmp data/*treams data/accountBalances output .archive /opt/hgcapp/*;
UseBonnevilleEVM=1 java -cp '.:data/lib/*:data/apps/*' -Dflag=1 com.hedera.node.app.ServicesMain -local 0

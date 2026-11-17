f=buffer_latestBlockOpened
p=`head -3000 /opt/hgcapp/services-hedera/HapiApp2.0/data/stats/MainNetStats*.csv | awk "/${f/\//\\\/}:/ {print NR + 1;}"`
awk -F, -v OFS='\t' "{print \$${p}}" /opt/hgcapp/services-hedera/HapiApp2.0/data/stats/MainNetStats*.csv | tail -n 1
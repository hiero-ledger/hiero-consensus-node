#!/usr/bin/env bash
#
# Copyright 2016-2022 Hedera Hashgraph, LLC
#
# This software is the confidential and proprietary information of
# Hedera Hashgraph, LLC. ("Confidential Information"). You shall not
# disclose such Confidential Information and shall use it only in
# accordance with the terms of the license agreement you entered into
# with Hedera Hashgraph.
#
# HEDERA HASHGRAPH MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SUITABILITY OF
# THE SOFTWARE, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
# TO THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
# PARTICULAR PURPOSE, OR NON-INFRINGEMENT. HEDERA HASHGRAPH SHALL NOT BE LIABLE FOR
# ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING, MODIFYING OR
# DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES.
#

cd "$(dirname "$0")"

# check if the names were supplied as arguments
if [[ -z "$*" ]]; then
  # if not, use a default set of names
  names=("node1" "node2" "node3" "node4")
else
  names=("$@")
fi

#names=(`cat names.txt`)

# Replace ("alice" "bob" ...) with the list of member names, separated by spaces.
# Or, replace the list with (`cat names.txt`) and then put all the names into names.txt.
# The names should all have their uppercase letters changed to lowercase.
# All spaces and punctuation should be deleted. All accents should be removed.
# So if the config.txt has the names "Alice", "Bob", and "Carol", the list here would
# need to be ("alice" "bob" "carol").
# A name like "5- John O'Donald, Sr." in the config.txt would need to be listed
# as "5johnodonaldsr" here. And if the "o" had an umlaut above it or a grave accent
# above it in the config.txt, then it would need to be entered as a plain "o" here.
# It is important that every name in the config.txt be different, even after making
# these changes. So the config.txt can't have two members with the name "Alice", nor can
# it have one member named "Alice" and another named "--alice--".

mkdir unused 2>/dev/null
mv *.pfx unused 2>/dev/null
rmdir unused 2>/dev/null

for nm in "${names[@]}"; do
  n="$(echo $nm | tr '[A-Z]' '[a-z]')"
  keytool -genkeypair -alias "s-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password" -dname "cn=s-$n" -keyalg "rsa" -sigalg "SHA384withRSA" -keysize "3072" -validity "36524"
  keytool -genkeypair -alias "a-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password" -dname "cn=a-$n" -keyalg "ec" -sigalg "SHA384withECDSA" -groupname "secp384r1" -validity "36524"
  keytool -genkeypair -alias "e-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password" -dname "cn=e-$n" -keyalg "ec" -sigalg "SHA384withECDSA" -groupname "secp384r1" -validity "36524"
  keytool -certreq -alias "a-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password" |
    keytool -gencert -alias "s-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password" -validity "36524" |
    keytool -importcert -alias "a-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password"
  keytool -certreq -alias "e-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password" |
    keytool -gencert -alias "s-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password" -validity "36524" |
    keytool -importcert -alias "e-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password"
  keytool -exportcert -alias "s-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password" |
    keytool -importcert -alias "s-$n" -keystore "public.pfx" -storetype "pkcs12" -storepass "password" -noprompt
  keytool -exportcert -alias "a-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password" |
    keytool -importcert -alias "a-$n" -keystore "public.pfx" -storetype "pkcs12" -storepass "password" -noprompt
  keytool -exportcert -alias "e-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password" |
    keytool -importcert -alias "e-$n" -keystore "public.pfx" -storetype "pkcs12" -storepass "password" -noprompt

  echo "--------------------"
  echo "file: private-$n.pfx"
  keytool -list -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password"
done

echo "--------------------"
echo "file: public.pfx"
keytool -list -keystore "public.pfx" -storetype "pkcs12" -storepass "password"
ls

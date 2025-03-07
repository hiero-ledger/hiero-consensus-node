#!/usr/bin/env bash
cd "`dirname "$0"`"

# check if the names were supplied as arguments
if [[ -z "$@" ]]; then
  # if not, use a default set of names
  names=("alice" "bob" "carol" "dave")
else
  names=( "$@" )
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

# When gen_steram_key is set to 1, an extra pair of key used for streaming will be generated
# and added to keystore

gen_stream_key=1

for nm in "${names[@]}"; do 
   n="$(echo $nm | tr '[A-Z]' '[a-z]')"
   keytool    -genkeypair -alias "s-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password" -dname "cn=s-$n" -keyalg "rsa" -sigalg "SHA384withRSA" -keysize "3072" -validity "36500"
   keytool    -genkeypair -alias "a-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password" -dname "cn=a-$n" -keyalg "ec" -sigalg "SHA384withECDSA" -keysize "384" -validity "36500"
   keytool    -genkeypair -alias "e-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password" -dname "cn=e-$n" -keyalg "ec" -sigalg "SHA384withECDSA" -keysize "384" -validity "36500"
   keytool    -certreq    -alias "a-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password"  |
      keytool -gencert    -alias "s-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password" -validity "36500"  |
      keytool -importcert -alias "a-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password" 
   keytool    -certreq    -alias "e-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password"  |
      keytool -gencert    -alias "s-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password" -validity "36500"  |
      keytool -importcert -alias "e-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password" 
   keytool    -exportcert -alias "s-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password"  |
      keytool -importcert -alias "s-$n" -keystore "public.pfx"     -storetype "pkcs12" -storepass "password"  -noprompt
   keytool    -exportcert -alias "a-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password"  |
      keytool -importcert -alias "a-$n" -keystore "public.pfx"     -storetype "pkcs12" -storepass "password"  -noprompt
   keytool    -exportcert -alias "e-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password"  |
      keytool -importcert -alias "e-$n" -keystore "public.pfx"     -storetype "pkcs12" -storepass "password"  -noprompt


   if [ $gen_stream_key == 1 ]
   then
     # generate stream key pair for client
     keytool  -genkeypair -alias "k-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password" -dname "cn=k-$n" -keyalg "ec" -sigalg "SHA384withECDSA" -keysize "384" -validity "36500"

     keytool    -certreq    -alias "k-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password"  |
      keytool -gencert    -alias "s-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password" -validity "36500" |
      keytool -importcert -alias "k-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password" 

    keytool    -exportcert -alias "k-$n" -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password"  |
      keytool -importcert -alias "k-$n" -keystore "public.pfx"     -storetype "pkcs12" -storepass "password"  -noprompt  
   fi

   echo "--------------------"
   echo  "file: private-$n.pfx"
   keytool -list                        -keystore "private-$n.pfx" -storetype "pkcs12" -storepass "password"
done

if [ $gen_stream_key == 1 ]
then
    # generate stream key pair for server
     keytool  -genkeypair -alias "s-stream" -keystore "private-stream.pfx" -storetype "pkcs12" -storepass "password" -dname "cn=s-stream" -keyalg "rsa" -sigalg "SHA384withRSA" -keysize "3072" -validity "36500"

     keytool  -genkeypair -alias "k-stream" -keystore "private-stream.pfx" -storetype "pkcs12" -storepass "password" -dname "cn=k-stream" -keyalg "ec" -sigalg "SHA384withECDSA" -keysize "384" -validity "36500"

    # export certificate 
    keytool    -certreq    -alias "k-stream" -keystore "private-stream.pfx" -storetype "pkcs12" -storepass "password"  |
      keytool -gencert    -alias "s-stream" -keystore "private-stream.pfx" -storetype "pkcs12" -storepass "password" -validity "36500" |
      keytool -importcert -alias "k-stream" -keystore "private-stream.pfx" -storetype "pkcs12" -storepass "password" 

    # import to public.pfx
    keytool    -exportcert -alias "k-stream" -keystore "private-stream.pfx" -storetype "pkcs12" -storepass "password"  |
      keytool -importcert -alias "k-stream" -keystore "public.pfx"     -storetype "pkcs12" -storepass "password"  -noprompt       
fi

echo "--------------------"
echo "file: public.pfx"
keytool  -list                          -keystore "public.pfx"    -storetype "pkcs12"  -storepass "password"
ls
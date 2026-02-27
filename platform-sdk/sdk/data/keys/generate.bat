SETLOCAL 
REM set "JAVA_HOME=C:\Program Files\Java\jdk1.8.0_131"
set NAMES=(node1 node2 node3 node4)

REM Replace the JAVA_HOME line with where the jdk is stored. This can be found by searching 
REM the hard drive for "java" from the search box at the top of any folder window.
REM
REM Replace the NAMES with the list of member names, separated by spaces.
REM The names should all have their uppercase letters changed to lowercase.
REM All spaces and punctuation should be deleted. All accents should be removed.
REM So if the config.txt has the names "Alice", "Bob", and "Carol", the list here 
REM would need to be (alice bob carol). 
REM A name like "5- John O'Donald, Sr." in the config.txt would need to be listed 
REM as "5johnodonaldsr" here. And if the "o" had an umlaut above it or a grave accent 
REM above it in the config.txt, then it would need to be entered as a plain "o" here.
REM It is important that every name in the config.txt be different, even after making
REM these changes. So the config.txt can't have two members with the name "Alice", nor
REM could it have one member named "Alice" and another named "--alice--".

set "EXE=\bin\keytool.exe"
set KEYTOOL="%JAVA_HOME%%EXE%"

rmdir /q /s unused 
mkdir unused 
move /y *.pfx unused
rmdir /q unused 

for %%n in %names% do (
   %KEYTOOL%    -genkeypair -alias "s-%%n" -keystore "private-%%n.pfx" -storetype "pkcs12" -storepass "password" -dname "cn=s-%%n" -keyalg "rsa" -sigalg "SHA384withRSA" -keysize "3072" -validity "36500"
   %KEYTOOL%    -genkeypair -alias "a-%%n" -keystore "private-%%n.pfx" -storetype "pkcs12" -storepass "password" -dname "cn=a-%%n" -keyalg "ec" -sigalg "SHA384withECDSA" -keysize "384" -validity "36500"
   %KEYTOOL%    -genkeypair -alias "e-%%n" -keystore "private-%%n.pfx" -storetype "pkcs12" -storepass "password" -dname "cn=e-%%n" -keyalg "ec" -sigalg "SHA384withECDSA" -keysize "384" -validity "36500"
   %KEYTOOL%    -certreq    -alias "a-%%n" -keystore "private-%%n.pfx" -storetype "pkcs12" -storepass "password"  | %KEYTOOL% -gencert    -alias "s-%%n" -keystore "private-%%n.pfx" -storetype "pkcs12" -storepass "password"  | %KEYTOOL% -importcert -alias "a-%%n" -keystore "private-%%n.pfx" -storetype "pkcs12" -storepass "password" 
   %KEYTOOL%    -exportcert -alias "s-%%n" -keystore "private-%%n.pfx" -storetype "pkcs12" -storepass "password"  | %KEYTOOL% -importcert -alias "s-%%n" -keystore "public.pfx"      -storetype "pkcs12" -storepass "password"  -noprompt
   %KEYTOOL%    -exportcert -alias "a-%%n" -keystore "private-%%n.pfx" -storetype "pkcs12" -storepass "password"  | %KEYTOOL% -importcert -alias "a-%%n" -keystore "public.pfx"      -storetype "pkcs12" -storepass "password"  -noprompt
   %KEYTOOL%    -exportcert -alias "e-%%n" -keystore "private-%%n.pfx" -storetype "pkcs12" -storepass "password"  | %KEYTOOL% -importcert -alias "e-%%n" -keystore "public.pfx"      -storetype "pkcs12" -storepass "password"  -noprompt
   echo "--------------------"
   echo  "file: private-%%n.pfx"
   %KEYTOOL% -list                         -keystore "private-%%n.pfx" -storetype "pkcs12" -storepass "password"
)

echo "--------------------"
echo "file: public.pfx"
%KEYTOOL%  -list                           -keystore "public.pfx"      -storetype "pkcs12" -storepass "password"
ENDLOCAL
dir

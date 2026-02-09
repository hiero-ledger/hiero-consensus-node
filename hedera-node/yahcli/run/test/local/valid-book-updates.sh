#! /bin/sh

TAG=${1:-'0.1.0'}

set +e

BOOKS='book-with-duplicate-entry book-with-new-endpoints book-with-new-node-accounts book-with-new-node-id book-with-new-rsa-key'

cp localhost/sysfiles/addressBook.json addressBook.json.bkup
for BOOK in $BOOKS; do
  echo "Testing with book $BOOK"
  cp run/test/local/assets/${BOOK}.json localhost/sysfiles/addressBook.json

  docker run -v $(pwd):/launch yahcli:$TAG -p 2 sysfiles upload address-book

  if [ ! $? -eq 0 ]; then
    echo "FAILED on upload with book $BOOK"
  fi

  docker run -v $(pwd):/launch yahcli:$TAG -p 2 sysfiles download address-book

  diff -b localhost/sysfiles/addressBook.json run/test/local/assets/${BOOK}.json

  if [ ! $? -eq 0 ]; then
    echo "FAILED to match download of book $BOOK"
  fi
done
mv addressBook.json.bkup localhost/sysfiles/addressBook.json

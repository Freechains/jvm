#!/usr/bin/env bash

echo
echo "=== TESTS-LIKE ==="
echo

FC=/tmp/freechains
./tests-clean.sh

###############################################################################

freechains host create $FC/8400 8400
freechains host start $FC/8400 &
sleep 0.5
freechains --host=localhost:8400 chain join /
b1=`freechains --host=localhost:8400 --time=0 --sign=70CFFBAAD1E1B640A77E7784D25C3E535F1E5237264D1B5C38CB2C53A495B3FE4EC5AF592D177459D2338D07FFF9A9B64822EF5BE9E9715E8C63965DD2AF6ECB chain post / inline utf8 Hello_World`

b2=`freechains --host=localhost:8400 chain like post / 1 "$b1"`
diff <(echo $b2) <(echo "") || exit 1

b3=`freechains --host=localhost:8400 --time=86400000 chain like post / 1 "$b1"`
j3=`freechains --host=localhost:8400 chain get / $b3`
d31=`jq ".hashable.refs" <(echo $j3)`
d32='[ "1_B865A4B2F2C79A1BF4BE047932AD5108BA9B5AF13C711F7B369896C59BC07A2E" ]'
diff <(echo $d31) <(echo $d32) || exit 1
exit 0
b4=`freechains --host=localhost:8400 chain like post / 13- "$b1" --why="hated it"`
j4=`freechains --host=localhost:8400 chain get / $b4`
d41=`jq ".hashable.like" <(echo $j4)`
diff <(echo $d41) <(echo '{ "n": -13, "pubkey": "4EC5AF592D177459D2338D07FFF9A9B64822EF5BE9E9715E8C63965DD2AF6ECB" }') || exit 1

# 0 <- Hello <- +1 <- -13

###############################################################################

freechains host create $FC/8401 8401
freechains host start $FC/8401 &
sleep 0.5
freechains --host=localhost:8401 chain join /

###############################################################################

echo
echo "=== ALL TESTS PASSED ==="
echo
#!/usr/bin/env bash

echo
echo "=== TESTS-LIKE ==="
echo

FC=/tmp/freechains
./clean.sh

PVT=70CFFBAAD1E1B640A77E7784D25C3E535F1E5237264D1B5C38CB2C53A495B3FE4EC5AF592D177459D2338D07FFF9A9B64822EF5BE9E9715E8C63965DD2AF6ECB

###############################################################################

freechains host create $FC/8400 8400
freechains host start $FC/8400 &
sleep 0.5
freechains --host=localhost:8400 host now 0
freechains --host=localhost:8400 chain join /
b1=`freechains --host=localhost:8400 chain post / inline utf8 Hello_World`
freechains --host=localhost:8400 --sign=$PVT chain post / inline utf8 Hello_World
freechains --host=localhost:8400 host now 8000000

b2=`freechains --host=localhost:8400 --sign=$PVT chain like post / + 1000 "$b1"`
diff <(echo $b2) <(echo "") || exit 1

freechains --host=localhost:8400 host now 90000000
echo $b1
b3=`freechains --host=localhost:8400 --sign=$PVT chain like post / + 1000 "$b1"`
j3=`freechains --host=localhost:8400 chain get / $b3`
d31=`jq ".immut.refs" <(echo $j3)`
d32="[ \"$b1\" ]"
diff <(echo $d31) <(echo $d32) || exit 1

freechains --host=localhost:8400 host now 180000000
b4=`freechains --host=localhost:8400 --sign=$PVT chain like post / - 1000 "$b1" --why="hated it"`
j4=`freechains --host=localhost:8400 chain get / $b4`
d41=`jq ".immut.like" <(echo $j4)`
diff <(echo $d41) <(echo "{ \"n\": -500, \"type\": \"POST\", \"ref\": \"$b1\" }") || exit 1

# 0 <- Hello <- +1 <- -13

###############################################################################

echo
echo "=== ALL TESTS PASSED ==="
echo
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

b2=`freechains --host=localhost:8400 chain like / 1 "$b1"`
j2=`freechains --host=localhost:8400 chain get / $b2`
d1=`jq ".hashable.refs" <(echo $j2)`
d2='[ "1_9EAD2D0A15871A87496F111C493F185290EDAF82AD5829CDFCD7E7E6A1F5F00A" ]'
diff <(echo $d1) <(echo $d2) || exit 1

b2=`freechains --host=localhost:8400 chain like / 13- "$b1" --why="hated it"`
j2=`freechains --host=localhost:8400 chain get / $b2`
d1=`jq ".hashable.like" <(echo $j2)`
diff <(echo $d1) <(echo '{ "first": -13, "second": "4EC5AF592D177459D2338D07FFF9A9B64822EF5BE9E9715E8C63965DD2AF6ECB" }') || exit 1

###############################################################################

echo
echo "=== ALL TESTS PASSED ==="
echo
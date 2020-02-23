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
b1=`freechains --host=localhost:8400 --time=0 chain post / inline utf8 Hello_World`

b2=`freechains --host=localhost:8400 chain like / 1 "$b1"`
j2=`freechains --host=localhost:8400 chain get / $b2`
d1=`jq ".hashable.refs" <(echo $j2)`
d2='[ "1_922F9EDAF6B3DACFD2513B5828676BC3FEC4D72AF6978B5DE99A606CCE17B521" ]'
diff <(echo $d1) <(echo $d2) || exit 1

b2=`freechains --host=localhost:8400 chain like / 13- "$b1" --why="hated it"`
j2=`freechains --host=localhost:8400 chain get / $b2`
d1=`jq ".hashable.like" <(echo $j2)`
diff <(echo $d1) <(echo "-13") || exit 1

###############################################################################

echo
echo "=== ALL TESTS PASSED ==="
echo
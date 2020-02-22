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
b1=`freechains --host=localhost:8400 --time=0 chain put / inline utf8 Hello_World`
b2=`freechains --host=localhost:8400 chain like / 1 "$b1"`
j2=`freechains --host=localhost:8400 chain get / $b2`
d1=`jq ".hashable.payload.ref" <(echo $j2)`
d2='"1_E59329F78884E68DCB8F470EF56DB0E535F4C2BE396A773F452B276C4EA82FAC"'
diff <(echo $d1) <(echo $d2) || exit 1

###############################################################################

echo
echo "=== ALL TESTS PASSED ==="
echo
#!/usr/bin/env bash

echo
echo "=== TESTS-LIKE ==="
echo

FC=/tmp/freechains
./clean.sh

H0=--host=localhost:8400

PVT0=6F99999751DE615705B9B1A987D8422D75D16F5D55AF43520765FA8C5329F7053CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322
PUB0=3CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322
SIG0=--sign=$PVT0

PUB1=E14E4D7E152272D740C3CA4298D19733768DF7E74551A9472AAE384E8AB34369
PVT1=6A416117B8F7627A3910C34F8B35921B15CF1AC386E9BB20E4B94AF0EDBE24F4E14E4D7E152272D740C3CA4298D19733768DF7E74551A9472AAE384E8AB34369
SIG1=--sign=$PVT1

###############################################################################

freechains host create $FC/8400 8400
freechains host start $FC/8400 &
sleep 0.5
freechains $H0 host now 0
freechains $H0 chain join /
b1=`freechains $H0 $SIG1 chain post / inline utf8 pub1.1`
b2=`freechains $H0 $SIG0 chain post / inline utf8 pub0.2`
freechains $H0 $SIG1 chain like post / + 6000 $b2

# b0 <- b1 <- b2 <- l3

# 2500
v1=`freechains $H0 chain like get / $PUB0`
diff <(echo $v1) <(echo 2000) || exit 1

# fail
f1=`freechains $H0 $SIG0 chain like post / + 1000 $b1`
diff <(echo $f1) <(echo "") || exit 1

freechains $H0 host now 9000000

b4=`freechains $H0 $SIG1 chain post / inline utf8 pub1.4`
l5=`freechains $H0 $SIG0 chain like post / + 1000 $b4`

# b0 <- b1 <- b2 <- l3 <- b4 <- l5

j5=`freechains $H0 chain get / $l5`
d31=`jq ".immut.like.like" <(echo $j5)`
d32="\"$b4\""
diff <(echo $d31) <(echo $d32) || exit 1

freechains $H0 host now 180000000

# b0 <- b1 <- b2 <- l3 <- b4 <- l5
#                            <- l5x

l5x=`freechains $H0 $SIG1 chain like post / - 500 "$b4" --why="hated it"`
j5x=`freechains $H0 chain get / $l5x`
d5x=`jq ".immut.like" <(echo $j5x)`
diff <(echo $d5x) <(echo "{ \"n\": -500, \"ref\": \"$b4\" }") || exit 1

v2=`freechains $H0 chain like get / $b4`
diff <(echo $v2) <(echo "250") || exit 1

###############################################################################

echo
echo "=== ALL TESTS PASSED ==="
echo
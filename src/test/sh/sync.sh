#!/usr/bin/env bash

echo
echo "=== TESTS-SYNC ==="
echo

FC=/tmp/freechains
./clean.sh

PVT0=6F99999751DE615705B9B1A987D8422D75D16F5D55AF43520765FA8C5329F7053CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322
PUB0=3CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322
S0=--sign=$PVT0

PUB1=E14E4D7E152272D740C3CA4298D19733768DF7E74551A9472AAE384E8AB34369
PVT1=6A416117B8F7627A3910C34F8B35921B15CF1AC386E9BB20E4B94AF0EDBE24F4E14E4D7E152272D740C3CA4298D19733768DF7E74551A9472AAE384E8AB34369
S1=--sign=$PVT1

H0=--host=localhost:8400
H1=--host=localhost:8401

###############################################################################
echo "#### 1"

freechains host create $FC/8400 8400
freechains host start $FC/8400 &
sleep 0.5
freechains $H0 chain join /

freechains host create $FC/8401 8401
freechains host start $FC/8401 &
sleep 0.5
freechains $H1 chain join /

freechains $H0 host now 0
freechains $H1 host now 0

freechains $H0 $S0 chain post / inline utf8 zero
freechains $H0 $S1 chain post / inline utf8 xxxx
freechains $H0 $S0 chain like / `freechains $H0 chain heads rejected /`

freechains $H0 chain send / localhost:8401

# h0 <- zero <-- lxxxx
#             \- xxxx

freechains $H0 host now 90000000
freechains $H1 host now 90000000

freechains $H0 chain post / inline utf8 111
freechains $H1 chain post / inline utf8 aaa

#                         111
# h0 <- zero <-- lxxxx <-/
#             \- xxxx <-/ \
#                          aaa

! diff -q -I localTime $FC/8400/chains/blocks/ $FC/8401/chains/blocks/ || exit 1

freechains $H0 $S0 chain like / `freechains $H0 chain heads rejected /`
freechains $H1 $S1 chain like / `freechains $H1 chain heads rejected /`

#                         111
# h0 <- zero <-- lxxxx <-/  <-- l111
#             \- xxxx <-/ \ <-- laaa
#                          aaa

freechains $H0 host now 98000000
freechains $H1 host now 98000000

freechains $H0 chain send / localhost:8401
freechains $H1 chain send / localhost:8400

diff -I localTime $FC/8400/chains/blocks/ $FC/8401/chains/blocks/ || exit 1

###############################################################################

echo
echo "=== ALL TESTS PASSED ==="
echo
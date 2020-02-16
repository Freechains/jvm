#!/usr/bin/env sh

FC=/tmp/freechains

while : ; do
  ./tests-shared.sh || exit 1
  ./tests-pubpvt.sh || exit 1
  ./tests-clean.sh

###############################################################################
echo "#### 1"

freechains host create $FC/8400 8400
jq ".timestamp=false" $FC/8400/host > /tmp/host.tmp && mv /tmp/host.tmp $FC/8400/host
freechains host start $FC/8400 &
sleep 0.5
freechains --host=localhost:8400 chain create /0
g=`freechains --host=localhost:8400 chain genesis /0`
h=`freechains --host=localhost:8400 chain put /0 inline utf8 Hello_World`
freechains --host=localhost:8400 chain get /0 "$h" > $FC/freechains-tests-get-1.out
freechains --host=localhost:8400 chain get /0 0_03170A2E7597B7B7E3D84C05391D139A62B157E78786D8C082F29DCF4C111314 > $FC/freechains-tests-get-0.out
hs=`freechains --host=localhost:8400 chain heads /0`
freechains --host=localhost:8400 chain get /0 "$g" > $FC/freechains-tests-gen.out
freechains --host=localhost:8400 chain get /0 "$hs" > $FC/freechains-tests-heads.out

diff $FC/freechains-tests-gen.out   out/freechains-tests-get-0.out || exit 1
diff $FC/freechains-tests-get-0.out out/freechains-tests-get-0.out || exit 1
diff $FC/freechains-tests-get-1.out out/freechains-tests-get-1.out || exit 1
diff $FC/freechains-tests-heads.out out/freechains-tests-get-1.out || exit 1

h=`freechains --host=localhost:8400 chain put /0 file base64 /bin/cat`
freechains --host=localhost:8400 chain get /0 "$h" > $FC/cat.node
jq ".hashable.payload" $FC/cat.node | tr -d '"' | base64 --decode > $FC/cat
diff $FC/cat /bin/cat || exit 1

###############################################################################
echo "#### 2"

freechains host create $FC/8401 8401
freechains host start $FC/8401 &
sleep 0.5
freechains --host=localhost:8401 chain create /0
freechains --host=localhost:8400 chain put /0 inline utf8 111
freechains --host=localhost:8400 chain put /0 inline utf8 222
freechains --host=localhost:8400 chain send /0 localhost:8401

diff $FC/8400/chains/0/ $FC/8401/chains/0/ || exit 1
ret=`ls $FC/8400/chains/0/ | wc`
if [ "$ret" != "      5       5     360" ]; then
  echo "$ret"
  exit 1
fi

###############################################################################
echo "#### 3"

while :
do
  rm -Rf $FC/8402
  freechains host create $FC/8402 8402
  freechains host start $FC/8402 &
  sleep 0.5
  freechains --host=localhost:8402 chain create /0
  freechains --host=localhost:8400 chain send /0 localhost:8402 &
  P1=$!
  freechains --host=localhost:8401 chain send /0 localhost:8402 &
  P2=$!
  wait $P1 $P2

  diff $FC/8401/chains/0/ $FC/8402/chains/0/ || exit 1
  ret=`ls $FC/8401/chains/0/ | wc`
  if [ "$ret" != "      5       5     360" ]; then
    echo "$ret"
    exit 1
  fi
  break
done

###############################################################################
###############################################################################
echo "#### 4"

for i in $(seq 1 50)
do
  freechains --host=localhost:8400 chain put /0 inline utf8 $i
done
freechains --host=localhost:8400 chain send /0 localhost:8401 &
P1=$!
freechains --host=localhost:8400 chain send /0 localhost:8402 &
P2=$!
wait $P1 $P2

diff $FC/8400/chains/0/ $FC/8401/chains/0/ || exit 1
diff $FC/8401/chains/0/ $FC/8402/chains/0/ || exit 1
ret=`ls $FC/8401/chains/0/ | wc`
if [ "$ret" != "     55      55    4005" ]; then
  echo "$ret"
  exit 1
fi

###############################################################################
echo "#### 5"

for i in $(seq 8411 8450)
do
  freechains host create $FC/$i $i
  freechains host start $FC/$i &
  sleep 0.5
  freechains --host=localhost:$i chain create /0
done

for i in $(seq 8411 8420)
do
  freechains --host=localhost:8400 chain send /0 localhost:$i &
done
sleep 10

for i in $(seq 8411 8420)
do
  diff $FC/8400/chains/0/ $FC/$i/chains/0/ || exit 1
done

for i in $(seq 8411 8420)
do
  freechains --host=localhost:$i chain send /0 localhost:$(($i+10)) &
done
sleep 10
for i in $(seq 8421 8430)
do
  freechains --host=localhost:$i chain send /0 localhost:$(($i+10)) &
  freechains --host=localhost:$i chain send /0 localhost:$(($i+20)) &
done
sleep 10

for i in $(seq 8421 8450)
do
  diff $FC/8400/chains/0/ $FC/$i/chains/0/ || exit 1
done

###############################################################################

done

echo
echo "=== ALL TESTS PASSED ==="
echo

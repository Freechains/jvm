#!/usr/bin/env sh

echo
echo "=== TESTS-GENERAL ==="
echo

FC=/tmp/freechains
./tests-clean.sh

###############################################################################
echo "#### 1"

freechains host create $FC/8400 8400
jq ".timestamp=false" $FC/8400/host > /tmp/host.tmp && mv /tmp/host.tmp $FC/8400/host
freechains host start $FC/8400 &
sleep 0.5
freechains --host=localhost:8400 chain create /
g=`freechains --host=localhost:8400 chain genesis /`
h=`freechains --host=localhost:8400 chain put / inline utf8 Hello_World`
freechains --host=localhost:8400 chain get / "$h" > $FC/freechains-tests-get-1.out
freechains --host=localhost:8400 chain get / 0_A45339EBC7CAC12BE09ACEA98410490DC2B2989DC114F2CC8DD5C274E1BC0595 > $FC/freechains-tests-get-0.out
hs=`freechains --host=localhost:8400 chain heads /`
freechains --host=localhost:8400 chain get / "$g" > $FC/freechains-tests-gen.out
freechains --host=localhost:8400 chain get / "$hs" > $FC/freechains-tests-heads.out

diff $FC/freechains-tests-gen.out   out/freechains-tests-get-0.out || exit 1
diff $FC/freechains-tests-get-0.out out/freechains-tests-get-0.out || exit 1
diff $FC/freechains-tests-get-1.out out/freechains-tests-get-1.out || exit 1
diff $FC/freechains-tests-heads.out out/freechains-tests-get-1.out || exit 1

h=`freechains --host=localhost:8400 chain put / file base64 /bin/cat`
freechains --host=localhost:8400 chain get / "$h" > $FC/cat.blk
jq ".hashable.payload" $FC/cat.blk | tr -d '"' | base64 --decode > $FC/cat
diff $FC/cat /bin/cat || exit 1

###############################################################################
echo "#### 2"

freechains host create $FC/8401 8401
freechains host start $FC/8401 &
sleep 0.5
freechains --host=localhost:8401 chain create /
freechains --host=localhost:8400 chain put / inline utf8 111
freechains --host=localhost:8400 chain put / inline utf8 222
freechains --host=localhost:8400 chain send / localhost:8401

diff $FC/8400/chains/blocks/ $FC/8401/chains/blocks/ || exit 1
ret=`ls $FC/8400/chains/blocks/ | wc`
if [ "$ret" != "      5       5     355" ]; then
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
  freechains --host=localhost:8402 chain create /
  freechains --host=localhost:8400 chain send / localhost:8402 &
  P1=$!
  freechains --host=localhost:8401 chain send / localhost:8402 &
  P2=$!
  wait $P1 $P2

  diff $FC/8401/chains/blocks/ $FC/8402/chains/blocks/ || exit 1
  ret=`ls $FC/8401/chains/blocks/ | wc`
  if [ "$ret" != "      5       5     355" ]; then
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
  freechains --host=localhost:8400 chain put / inline utf8 $i
done
freechains --host=localhost:8400 chain send / localhost:8401 &
P1=$!
freechains --host=localhost:8400 chain send / localhost:8402 &
P2=$!
wait $P1 $P2

diff $FC/8400/chains/blocks/ $FC/8401/chains/blocks/ || exit 1
diff $FC/8401/chains/blocks/ $FC/8402/chains/blocks/ || exit 1
ret=`ls $FC/8401/chains/blocks/ | wc`
if [ "$ret" != "     55      55    3950" ]; then
  echo "$ret"
  exit 1
fi

###############################################################################
echo "#### 5"

for i in $(seq 8411 8440)
do
  freechains host create $FC/$i $i
  freechains host start $FC/$i &
  sleep 0.5
  freechains --host=localhost:$i chain create /
done

for i in $(seq 8411 8420)
do
  freechains --host=localhost:8400 chain send / localhost:$i &
done
sleep 10

for i in $(seq 8411 8420)
do
  diff $FC/8400/chains/blocks/ $FC/$i/chains/blocks/ || exit 1
done

for i in $(seq 8411 8420)
do
  freechains --host=localhost:$i chain send / localhost:$(($i+10)) &
done
sleep 10
for i in $(seq 8421 8430)
do
  freechains --host=localhost:$i chain send / localhost:$(($i+10)) &
  freechains --host=localhost:$i chain send / localhost:$(($i+20)) &
done
sleep 10

for i in $(seq 8421 8440)
do
  diff $FC/8400/chains/blocks/ $FC/$i/chains/blocks/ || exit 1
done

###############################################################################
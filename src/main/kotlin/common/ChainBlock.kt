package org.freechains.common

import java.io.File

import com.goterl.lazycode.lazysodium.LazySodium
import com.goterl.lazycode.lazysodium.utils.Key
import org.freechains.platform.lazySodium

fun Chain.blockState (blk: Block) : State {
    // TODO: sqrt
    fun hasTime () : Boolean {
        return blk.localTime <= getNow()-T2H_past   // old enough
    }

    fun repsPostReject () : Boolean {
        val (pos,neg) = this.repsPost(blk.hash)
        return neg*LK23_rej - pos >= 0
    }

    return when {
        // unchangeable
        blk.immut.height <= 1  -> State.ACCEPTED      // first two blocks
        this.fromOwner(blk)    -> State.ACCEPTED      // owner signature
        blk.immut.like != null -> State.ACCEPTED      // a like

        // changeable
        blk.accepted           -> State.ACCEPTED      // TODO: remove
        repsPostReject()       -> State.REJECTED      // not enough reps
        ! hasTime()            -> State.PENDING       // not old enough
        else                   -> State.ACCEPTED      // enough reps, enough time
    }
}

fun Chain.blockChain (blk: Block) {
    this.blockAssert(blk)
    this.fsSaveBlock(blk)
    this.heads.add(blk.hash)
    this.reBacksFronts(blk)
    this.fsSave()
}

private fun Chain.reBacksFronts (blk: Block) {
    blk.immut.backs.forEach {
        this.heads.remove(it)
        this.fsLoadBlock(it, null).let {
            assert(!it.fronts.contains(blk.hash)) { it.hash + " -> " + blk.hash }
            it.fronts.add(blk.hash)
            it.fronts.sort()
            this.fsSaveBlock(it)
        }
    }
}

fun Chain.backsAssert (blk: Block) {
    // TODO 90d

    blk.immut.backs.forEach {
        assert(this.fsExistsBlock(it))      // all backs must exist
        this.fsLoadBlock(it,null).let {
            assert(it.immut.time <= blk.immut.time)        // all backs must be older

            if (blk.immut.isLikePub()) {
                // a post like must back to the ref only
                assert(blk.immut.like!!.ref==it.hash && blk.immut.backs.size==1)
            } else {
                // otherwise, all backs must be green
                assert(this.blockState(it) == State.ACCEPTED)
            }
        }
    }
}

fun Chain.blockAssert (blk: Block) {
    val imm = blk.immut
    assert(blk.hash == imm.toHash())        // hash matches immut
    this.backsAssert(blk)                   // backs exist and are older

    val now = getNow()
    assert (
        imm.time <= now+T30M_future &&      // not from the future
        imm.time >= now-T120D_past          // not too old
    )

    if (this.pub != null && this.pub.oonly) {
        assert(this.fromOwner(blk))         // signed by owner (if oonly is set)
    }

    val gen = this.getGenesis()      // unique genesis front (unique 1_xxx)
    if (blk.immut.backs.contains(gen)) {
        val b = this.fsLoadBlock(gen, null)
        assert(b.fronts.isEmpty() || b.fronts[0]==blk.hash) { "genesis is already referred" }
    }

    if (imm.like != null) {                 // like has reputation
        val n = imm.like.n
        val pub = blk.sign!!.pub
        assert(this.fromOwner(blk) || n <= this.repsPub(pub, imm.time)) {
            "not enough reputation"
        }
    }

    if (blk.sign != null) {                 // sig.hash/blk.hash/sig.pubkey all match
        val sig = LazySodium.toBin(blk.sign.hash)
        val msg = lazySodium.bytes(blk.hash)
        val key = Key.fromHexString(blk.sign.pub).asBytes
        assert(lazySodium.cryptoSignVerifyDetached(sig, msg, msg.size, key)) { "invalid signature" }
    }
}

// LIKE

fun Chain.fromOwner (blk: Block) : Boolean {
    return (this.pub != null) && (blk.sign != null) && (blk.sign.pub == this.pub.key)
}

package org.freechains.common

import com.goterl.lazycode.lazysodium.LazySodium
import com.goterl.lazycode.lazysodium.utils.Key
import org.freechains.platform.lazySodium

fun Chain.blockState (blk: Block) : State {
    // TODO: sqrt
    fun hasTime () : Boolean {
        return blk.localTime <= getNow()-T2H_past   // old enough
    }

    val rep = this.repsPost(blk.hash)

    return when {
        // unchangeable
        blk.immut.height <= 1   -> State.ACCEPTED      // first two blocks
        this.fromOwner(blk)     -> State.ACCEPTED      // owner signature
        blk.immut.like != null  -> State.ACCEPTED      // a like

        // changeable
        LK23_rej(rep)           -> State.REJECTED      // not enough reps
        ! hasTime()             -> State.PENDING       // not old enough
        else                    -> State.ACCEPTED      // enough reps, enough time
    }
}

fun Chain.blockChain (blk: Block) {
    // get old state of liked block
    val wasLiked=
        if (blk.immut.like == null)
            null
        else
            this.blockState(this.fsLoadBlock(blk.immut.like.ref,null))

    this.blockAssert(blk)
    this.fsSaveBlock(blk)
    this.heads.add(blk.hash)

    // add new front of backs
    blk.immut.backs.forEach {
        this.heads.remove(it)
        this.fsLoadBlock(it, null).let {
            assert(!it.fronts.contains(blk.hash)) { it.hash + " -> " + blk.hash }
            it.fronts.add(blk.hash)
            it.fronts.sort()
            this.fsSaveBlock(it)
        }
    }

    // check if state of liked block changed
    if (wasLiked != null) {
        this.fsLoadBlock(blk.immut.like!!.ref,null).let {
            val now = this.blockState(it)
            //println("${it.hash} : $wasLiked -> $now")
            when {
                // changed from ACC -> REJ
                (wasLiked==State.ACCEPTED && now==State.REJECTED) -> {
                    // remove each front recursively from this.heads
                    it.fronts.forEach { this.blockRejectBan(it,false) }
                }
                // changed state
                (wasLiked==State.REJECTED && now==State.ACCEPTED) -> {
                    // change to PENDING
                    it.localTime = getNow()
                    this.fsSaveBlock(it)
                }
            }
        }
    }

    this.fsSave()
}

fun Chain.backsAssert (blk: Block) {
    // TODO 90d

    blk.immut.backs.forEach {
        //println("$it <- ${blk.hash}")
        assert(this.fsExistsBlock(it)) { "back must exist" }
        this.fsLoadBlock(it,null).let {
            assert(it.immut.time <= blk.immut.time) { "back must be older"}
            if (blk.immut.like == null) {
                assert(this.blockState(it) == State.ACCEPTED) { "backs must be accepted" }
            } else {
                assert(blk.immut.like.ref==it.hash && blk.immut.backs.size==1) { "like must back single ref only" }
            }
        }
    }
}

fun Chain.blockAssert (blk: Block) {
    val imm = blk.immut
    assert(blk.hash == imm.toHash()) { "hash must verify" }
    this.backsAssert(blk)                   // backs exist and are older

    val now = getNow()
    assert(imm.time <= now+T30M_future) { "from the future" }
    assert(imm.time >= now-T120D_past) { "too old" }

    if (this.pub != null && this.pub.oonly) {
        assert(this.fromOwner(blk)) { "must be from owner" }
    }

    val gen = this.getGenesis()      // unique genesis front (unique 1_xxx)
    if (blk.immut.backs.contains(gen)) {
        val b = this.fsLoadBlock(gen, null)
        assert(b.fronts.isEmpty() || b.fronts[0]==blk.hash) { "genesis is already referred" }
    }

    if (imm.like != null) {
        val n = imm.like.n
        val pub = blk.sign!!.pub
        assert(this.fsExistsBlock(imm.like.ref)) {
            "like target not found"         // like has target
        }
        assert(this.fromOwner(blk) || n <= this.repsAuthor(pub,imm)) {
            "not enough reputation"         // like has reputation
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

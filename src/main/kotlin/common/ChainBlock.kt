package org.freechains.common

import com.goterl.lazycode.lazysodium.LazySodium
import com.goterl.lazycode.lazysodium.utils.Key
import org.freechains.platform.lazySodium
import kotlin.math.sqrt

fun Chain.fromOwner (blk: Block) : Boolean {
    return (this.pub != null) && (blk.sign != null) && (blk.sign.pub == this.pub.key)
}

fun Chain.hashState (hash: Hash) : State {
    return when {
        this.fsExistsBlock(hash,"/banned/") -> State.BANNED
        ! this.fsExistsBlock(hash)               -> State.MISSING
        else -> this.blockState(this.fsLoadBlock(hash,null))
    }
}

fun Chain.blockState (blk: Block) : State {
    fun hasTime () : Boolean {
        val now = getNow()
        val dt = now - blk.immut.time
        return blk.localTime <= now - (T2H_past + sqrt(dt.toFloat()))   // old enough
    }

    val rep = this.repsPost(blk.hash)

    return when {
        // unchangeable
        blk.immut.height <= 1   -> State.ACCEPTED      // first two blocks
        this.fromOwner(blk)     -> State.ACCEPTED      // owner signature
        this.trusted            -> State.ACCEPTED      // chain with trusted hosts/authors
        blk.immut.like != null  -> State.ACCEPTED      // a like

        // changeable
        LK23_500_rej(rep)       -> State.REJECTED      // not enough reps
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
    for (bk in blk.immut.backs) {
        this.heads.remove(bk)
        this.fsLoadBlock(bk, null).let {
            //assert(!it.fronts.contains(blk.hash)) { it.hash + " -> " + blk.hash }
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
                    this.blockReject(it.hash)
                }
                // changed from REJ -> ACC
                (wasLiked==State.REJECTED && now==State.ACCEPTED) -> {
                    this.blockUnReject(it.hash)
                }
            }
        }
    }

    this.fsSave()
}

fun Chain.backsAssert (blk: Block) {
    for (bk in blk.immut.backs) {
        //println("$it <- ${blk.hash}")
        assert(this.fsExistsBlock(bk)) { "back must exist" }
        this.fsLoadBlock(bk,null).let {
            assert(it.immut.time <= blk.immut.time) { "back must be older"}
            if (blk.immut.like == null) {
                assert(this.blockState(it) == State.ACCEPTED) { "backs must be accepted" }
            }
        }
    }
}

fun Chain.blockAssert (blk: Block) {
    assert(! this.fsExistsBlock(blk.hash,"/banned/")) { "block is banned" }

    val imm = blk.immut
    assert(blk.hash == imm.toHash()) { "hash must verify" }
    this.backsAssert(blk)                   // backs exist and are older

    val now = getNow()
    assert(imm.time <= now+T30M_future) { "from the future" }
    assert(imm.time >= now-T120D_past) { "too old" }

    val gen = this.getGenesis()      // unique genesis front (unique 1_xxx)
    if (blk.immut.backs.contains(gen)) {
        val b = this.fsLoadBlock(gen, null)
        assert(b.fronts.isEmpty() || b.fronts[0]==blk.hash) { "genesis is already referred" }
    }

    if (this.pub!=null && this.pub.oonly) {
        assert(this.fromOwner(blk)) { "must be from owner" }
    }

    if (blk.sign != null) {                 // sig.hash/blk.hash/sig.pubkey all match
        val sig = LazySodium.toBin(blk.sign.hash)
        val msg = lazySodium.bytes(blk.hash)
        val key = Key.fromHexString(blk.sign.pub).asBytes
        assert(lazySodium.cryptoSignVerifyDetached(sig, msg, msg.size, key)) { "invalid signature" }

        // check if new post leads to latest post from author currently in the chain
        val prvs = this.traverseFromHeads(this.heads,true) {
            it.sign == null || it.sign.pub != blk.sign.pub
        }
        println(prvs.contentToString())
        fun leadsTo (cur: Block) : Boolean {
            return (
                (cur.hash == prvs.last().hash)    ||
                cur.immut.backs.any {
                        leadsTo(this.fsLoadBlock(it,null))
                }
            )
        }
        assert(leadsTo(blk)) { "must lead to author's previous post" }
    }

    if (imm.like != null) {
        val n = imm.like.n
        val pub = blk.sign!!.pub
        assert(this.fsExistsBlock(imm.like.ref)) {
            "like target not found"         // like has target
        }
        //println("n=$n // loc=${this.repsAuthor(pub,imm)} // glb=${this.repsAuthor(pub,null)}")

        val n_ = this.fsLoadBlock(imm.like.ref,null).let {
            when {
                (it.sign == null)             -> n
                (it.sign.pub == blk.sign.pub) -> n - n/2
                (it.sign.pub != blk.sign.pub) -> n
                else -> error("impossible case")
            }
        }

        assert (
            this.fromOwner(blk) ||   // owner has infinite reputation
            this.trusted               ||   // dont check reps (private chain)
            (
                // global no // local ok  --> double spend
                // global ok // local no  --> use + from cousins (which may be rejected)
                n_ <= this.repsAuthor(pub,imm) &&        // local reputation (backs)
                n_ <= this.repsAuthor(pub,null)    // global reputation (heads)
            )
        ) {
            "not enough reputation"         // like has reputation
        }
    }
}

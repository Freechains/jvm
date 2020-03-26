package org.freechains.common

import com.goterl.lazycode.lazysodium.LazySodium
import com.goterl.lazycode.lazysodium.interfaces.Sign
import com.goterl.lazycode.lazysodium.utils.Key
import org.freechains.platform.lazySodium
import kotlin.math.sqrt

fun Chain.fromOwner (blk: Block) : Boolean {
    return (this.pub != null) && blk.isFrom(this.pub.key)
}

// STATE

/*
fun Chain.nonRejectedPrev (pub: String) : Block? {
    fun rec (blk: Block?) : Block? {
        return when {
            (blk == null) -> null
            (this.blockState(blk) != State.REJECTED) -> blk
            else          -> rec(this.fsLoadBlock(blk.immut.prev!!,null))
        }
    }
    return rec(this.findAuthorLast(pub))
}
*/

fun Chain.hashState (hash: Hash) : State {
    return when {
        this.fsExistsBlock(hash,"/bans/") -> State.BANNED
        ! this.fsExistsBlock(hash)            -> State.MISSING
        else -> this.blockState(this.fsLoadBlock(hash,null))
    }
}

fun Chain.blockState (blk: Block) : State {
    fun oldEnough () : Boolean {
        val dt = blk.localTime - blk.immut.time
        return blk.localTime <= getNow() - (T2H_past + sqrt(dt.toFloat()))   // old enough
    }

    val prev = blk.immut.prev
    val ath = when {
        (blk.sign == null) -> 0     // anon post, no author reps
        (prev == null)     -> 0     // no prev post, no author reps
        else -> this.repsAuthor (
            blk.sign.pub,
            blk.immut.time,         // author rep at the time of block
            listOf(prev)
        )
    }
    val reps = this.repsPost(blk.hash, true)

    // number of blocks that point back to it (-1 myself)
    //val fronts = max(0, this.bfsAll(blk.hash).count{ this.blockState(it)==State.ACCEPTED } - 1)

    //println("rep ${blk.hash} = reps=$reps + ath=$ath + fronts=$fronts")
    return when {
        // unchangeable
        (blk.hash.toHeight() <= 1)  -> State.ACCEPTED      // first two blocks
        this.fromOwner(blk)         -> State.ACCEPTED      // owner signature
        this.trusted                -> State.ACCEPTED      // chain with trusted hosts/authors
        (blk.immut.like != null)    -> State.ACCEPTED      // a like

        // changeable
        (reps+ath <= 0)             -> State.REJECTED      // not enough reps
        (! oldEnough())             -> State.PENDING       // not old enough
        else                        -> State.ACCEPTED      // enough reps, enough time
    }
}

// NEW

fun Chain.blockNew (imm_: Immut, sign: HKey?, crypt: HKey?) : Block {
    assert(imm_.prev == null) { "prev must be null" }

    val prev = sign?.let { this.findAuthorLast(it.pvtToPub()) } ?.hash

    /*
    val prev = sign?.let { s -> this
        .findAuthorLast(s.pvtToPub())
        ?.let {
            fun rec (blk: Block) : Block? {
                return when {
                    (this.blockState(blk) != State.REJECTED) -> blk
                    (blk.immut.prev == null)                 -> null
                    else                                     -> rec(this.fsLoadBlock(blk.immut.prev,null))
                }
            }
            rec(it)
        }
        ?.hash
    }
    */

    //println("pay=${imm_.payload} // ${(prev!=null && imm_.like!=null && this.isFromTo(imm_.like.hash,prev))}")

    //assert(imm_.backs.isEmpty()) { "backs must be empty" }
    val accs = this.getHeads(State.ACCEPTED).toTypedArray()
    val backs = when {
        imm_.backs.isNotEmpty() -> imm_.backs
        (imm_.like == null)     -> accs
        else -> {
            val liked = this.fsLoadBlock(imm_.like.hash, null)
            when {
                // engraved, no reason to move this like out
                ((liked.immut.time <= imm_.time-T1D_rep_eng))      -> accs

                // author has post after liked, cannot move it
                (prev!=null && this.isFromTo(imm_.like.hash,prev)) -> accs

                // move backs to before the liked post
                else                                               -> accs
                    .map {
                        if (this.isFromTo(imm_.like.hash, it)) {
                            this.fsLoadBlock(imm_.like.hash,null).immut.backs.toList()
                        } else {
                            listOf(it)
                        }
                    }
                    .toList()
                    .flatten()
                    .toTypedArray()
                    //.toSet()
                    //.toList()
            }
        }
    }

    // must include author's prev if not rejected
    val backs2 =
        if (prev == null) {
            backs
        } else {
            val ath = this.findAuthorLast(sign.pvtToPub())
            if (ath == null) {
                backs
            } else {
                when (this.blockState(ath)) {
                    State.REJECTED -> error("cannot link to rejected")
                    State.ACCEPTED -> backs
                    State.PENDING  -> backs
                        .toSet()
                        .plusElement(ath.hash)
                        .minus(backs.filter { this.isFromTo(it,ath.hash) })
                        .toTypedArray()
                    else -> error("bug found: invalid state")
                }
            }
        }

    val imm = imm_.copy (
        crypt   = (crypt != null),
        payload = if (crypt == null) imm_.payload else imm_.payload.encrypt(crypt),
        prev    = prev,
        backs   = backs2
    )
    val hash = imm.toHash()

    // signs message if requested (pvt provided or in pvt chain)
    val signature=
        if (sign == null)
            null
        else {
            val sig = ByteArray(Sign.BYTES)
            val msg = lazySodium.bytes(hash)
            val pvt = Key.fromHexString(sign).asBytes
            lazySodium.cryptoSignDetached(sig, msg, msg.size.toLong(), pvt)
            val sig_hash = LazySodium.toHex(sig)
            Signature(sig_hash, sign.pvtToPub())
        }

    val new = Block(imm, hash, signature)
    this.blockChain(new)
    return new
}

fun Chain.blockChain (blk: Block) {
    this.blockAssert(blk)
    this.fsSaveBlock(blk)

    // addBlockAsFrontOfBacks
    for (bk in blk.immut.backs) {
        this.fsLoadBlock(bk, null).let {
            assert(!it.fronts.contains(blk.hash)) { "bug found: " + it.hash + " -> " + blk.hash }
            it.fronts.add(blk.hash)
            //it.fronts.sort()
            this.fsSaveBlock(it)
        }
    }
}

fun Chain.backsAssert (blk: Block) {
    for (bk in blk.immut.backs) {
        //println("$it <- ${blk.hash}")
        assert(this.fsExistsBlock(bk)) { "back must exist" }
        this.fsLoadBlock(bk,null).let {
            assert(it.immut.time <= blk.immut.time) { "back must be older"}
            when {
                (this.blockState(it) == State.ACCEPTED) -> true
                (blk.immut.prev == null)                -> false
                else -> this.findAuthorLast(blk.sign!!.pub).let { (it!=null && this.blockState(it)!=State.REJECTED) }
            }.let {
                assert(it) { "backs must be accepted" }
            }
        }
    }
}

fun Chain.blockAssert (blk: Block) {
    assert(! this.fsExistsBlock(blk.hash,"/bans/")) { "block is banned" }

    val imm = blk.immut
    assert(blk.hash == imm.toHash()) { "hash must verify" }
    this.backsAssert(blk)                   // backs exist and are older

    val now = getNow()
    assert(imm.time <= now+T30M_future) { "from the future" }
    assert(imm.time >= now-T120D_past) { "too old" }

    val gen = this.getGenesis()      // unique genesis front (unique 1_xxx)
    if (blk.immut.backs.contains(gen)) {
        this
            .bfsAll()
            .filter { it.hash.toHeight() == 1 }
            .let {
                assert(it.isEmpty()) { "genesis is already referred" }
            }
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
        this.bfsFirst(this.getHeads(State.ALL)) { it.isFrom(blk.sign.pub) }.let {
            //if (it != null) println("${imm.prev} --> ${blk.hash} = ${this.isFromTo(imm.prev!!,blk.hash)}")
            assert (
                if (it == null)
                    (imm.prev == null)
                else
                    (imm.prev==it.hash) && (this.hashState(it.hash)!=State.REJECTED)
                        //&& (imm.backs.any { this.isFromTo(imm.prev,it) })
            ) { "must point to author's previous post" }
        }
    }

    if (imm.like != null) {
        assert(blk.sign != null) { "like must be signed"}
        assert(this.fsExistsBlock(imm.like.hash)) { "like must have valid target" }
        this.fsLoadBlock(imm.like.hash,null).let {
            assert(!it.isFrom(blk.sign!!.pub)) { "like must not target itself" }
        }
        assert (
            this.fromOwner(blk) ||   // owner has infinite reputation
            this.trusted               ||   // dont check reps (private chain)
            this.repsAuthor(blk.sign!!.pub,imm.time) > 0
        ) {
            "like author must have reputation"
        }
    }
}

// BAN

fun Chain.blockBan (hash: Hash) {
    //println("rem $hash // ${blk.fronts}")
    val blk = this.fsLoadBlock(hash, null)

    // remove myself as front of all my backs
    for (bk in blk.immut.backs) {
        this.fsLoadBlock(bk, null).let {
            it.fronts.remove(hash)
            this.fsSaveBlock(it)
        }
    }

    for (fr in blk.fronts) {
        this.blockBan(fr)
    }

    this.fsSaveBlock(blk, "/bans/")
    this.fsRemBlock(blk.hash)
}


fun Chain.blockUnban (hash: Hash) {
    //println("rem $hash // ${blk.fronts}")
    val blk = this.fsLoadBlock(hash, null, "/bans/")

    // add myself as front of all my backs
    for (bk in blk.immut.backs) {
        this.fsLoadBlock(bk, null).let {
            it.fronts.add(hash)
            this.fsSaveBlock(it)
        }
    }

    blk.fronts.clear()

    this.fsSaveBlock(blk)
    this.fsRemBlock(blk.hash, "/bans/")

}
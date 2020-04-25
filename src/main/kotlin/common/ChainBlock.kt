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

fun Chain.hashState (hash: Hash, now: Long) : State {
    return when {
        ! this.fsExistsBlock(hash) -> State.MISSING
        else -> this.blockState(this.fsLoadBlock(hash,null), now)
    }
}

fun Chain.blockState (blk: Block, now: Long) : State {
    val prev = blk.immut.prev
    val ath = when {
        (blk.sign == null) -> 0     // anon post, no author reps
        (prev == null)     -> 0     // no prev post, no author reps
        else               -> this.repsAuthor(blk.sign.pub, now, listOf(prev))
    }
    val (pos,neg) = this.repsPost(blk.hash)

    // number of blocks that point back to it (-1 myself)
    //val fronts = max(0, this.bfsAll(blk.hash).count{ this.blockState(it)==State.ACCEPTED } - 1)

    //println("rep ${blk.hash} = reps=$pos-$neg + ath=$ath // ${blk.immut.time}")
    return when {
        // unchangeable
        (blk.hash.toHeight() <= 1)  -> State.ACCEPTED       // first two blocks
        this.fromOwner(blk)         -> State.ACCEPTED       // owner signature
        this.trusted                -> State.ACCEPTED       // chain with trusted hosts/authors only
        (blk.immut.like != null)    -> State.ACCEPTED       // a like

        // changeable
        (pos==0 && ath<=0)          -> State.BLOCKED        // no likes && noob author
        (2*neg >= pos)              -> State.REJECTED       // too much dislikes
        else                        -> State.ACCEPTED
    }
}

// NEW

fun Chain.blockNew (imm_: Immut, sign: HKey?, crypt: HKey?) : Block {
    assert(imm_.prev == null) { "prev must be null" }

    val backs= if (imm_.backs.isNotEmpty()) imm_.backs else this.getHeads(State.LINKED).toTypedArray()

    // must point to liked block directly or indirectly
    val backs2 = when {
        (imm_.like == null) -> backs
        backs.any {
            this.bfsFrontsIsFromTo(imm_.like.hash, it)
        }                   -> backs
        else                -> backs + arrayOf(imm_.like.hash)
    }

    // TODO: why should include authors' prev?
    /*
    // must include author's prev if not rejected
    val prev = sign?.let { this.bfsBacksFindAuthor(it.pvtToPub()) } ?.hash
    val ath = if (sign == null) null else this.bfsBacksFindAuthor(sign.pvtToPub())
    val backs2 = when {
        (prev == null) -> backs
        (ath == null)  -> backs
        (this.blockState(ath,imm_.time) == State.ACCEPTED) -> backs
        else -> backs
            .toSet()
            .plusElement(ath.hash)
            .minus(backs.filter { this.bfsFrontsIsFromTo(it,ath.hash) })
            .toTypedArray()
    }
    */

    val imm = imm_.copy (
        crypt   = (crypt != null),
        payload = if (crypt == null) imm_.payload else imm_.payload.encrypt(crypt),
        prev    = sign?.let { this.bfsBacksFindAuthor(it.pvtToPub()) } ?.hash,
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
            it.fronts.sort()            // TODO: for external tests in FS (sync.sh)
            this.fsSaveBlock(it)
        }
    }
}

fun Chain.backsAssert (blk: Block) {
    for (bk in blk.immut.backs) {
        //println("$it <- ${blk.hash}")
        assert(this.fsExistsBlock(bk)) { "back must exist" }
        this.fsLoadBlock(bk,null).let { bbk ->
            assert(bbk.immut.time <= blk.immut.time) { "back must be older"}
            when {
                (this.blockState(bbk,blk.immut.time) == State.ACCEPTED) -> true
                (blk.immut.prev == null)                                -> false
                else -> this.bfsBacksFindAuthor(blk.sign!!.pub).let {
                    (it!=null && this.blockState(it,blk.immut.time)!=State.REJECTED)
                }
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
    if (imm.backs.contains(gen)) {
        this
            .bfsFrontsAll()
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
        this.bfsBacksFirst(this.getHeads(State.ALL)) { it.isFrom(blk.sign.pub) }.let {
            //if (it != null) println("${imm.prev} --> ${blk.hash} = ${this.isFromTo(imm.prev!!,blk.hash)}")
            assert (
                if (it == null)
                    (imm.prev == null)
                else
                    (imm.prev==it.hash)
            ) { "must point to author's previous post" }
        }
    }

    if (imm.like != null) {
        assert(blk.sign != null) { "like must be signed"}
        // may receive out of order // may point to rejected post
        //assert(this.fsExistsBlock(imm.like.hash)) { "like must have valid target" }
        if (this.fsExistsBlock(imm.like.hash)) {
            this.fsLoadBlock(imm.like.hash,null).let {
                assert(!it.isFrom(blk.sign!!.pub)) { "like must not target itself" }
            }
        }
        assert (
            this.fromOwner(blk) ||   // owner has infinite reputation
            this.trusted               ||   // dont check reps (private chain)
            this.repsAuthor(blk.sign!!.pub, imm.time, imm.backs.toList()) > 0
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
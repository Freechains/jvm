package org.freechains.common

import com.goterl.lazycode.lazysodium.LazySodium
import com.goterl.lazycode.lazysodium.interfaces.Sign
import com.goterl.lazycode.lazysodium.utils.Key
import org.freechains.platform.lazySodium
import kotlin.math.max

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

    val unit = blk.hash.toHeight().toReps()

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
        (pos==0 && ath<unit)        -> State.BLOCKED        // no likes && noob author
        (2*neg >= pos)              -> State.HIDDEN       // too much dislikes
        else                        -> State.ACCEPTED
    }
}

// NEW

fun Chain.blockNew (imm_: Immut, pay_: String, sign: HKey?, crypt: HKey?) : Block {
    assert(imm_.time      == 0.toLong()) { "time must not be set" }
    assert(imm_.pay.hash  == "")         { "pay must not be set" }
    assert(imm_.prev      == null)       { "prev must not be set" }

    assert(imm_.backs.isEmpty())
    val backs = this.getHeads(State.LINKED)
        .let { backs ->
            backs.plus (
                // must point to liked block
                when {
                    (imm_.like == null) -> emptyArray()
                    backs.any {
                        this.bfsFrontsIsFromTo(
                            imm_.like.hash,
                            it
                        )  // already does indirectly (it points to some of the heads)
                    } -> emptyArray()
                    else -> arrayOf(imm_.like.hash)  // point directly (it was blocked)
                }
            )
        }
        .let { backs ->
            this.bfsCleanHeads(backs.toList())
        }

    val imm = imm_.copy (
        time = max (
            getNow(),
            1 + backs.map { this.fsLoadBlock(it, null).immut.time }.max()!!
        ),
        pay = imm_.pay.copy (
            crypt = (crypt != null),
            hash  = pay_.calcHash()
        ),
        prev  = sign?.let { this.bfsBacksFindAuthor(it.pvtToPub()) } ?.hash,
        backs = backs.toTypedArray()
    )
    val pay = if (crypt == null) pay_ else pay_.encrypt(crypt)
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

    val new = Block(imm, hash, pay, signature)
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

    this.heads.add(blk.hash)
    blk.immut.backs.forEach { this.heads.remove(it) }
    this.fsSave()
}

fun Chain.backsAssert (blk: Block) {
    for (bk in blk.immut.backs) {
        //println("$it <- ${blk.hash}")
        assert(this.fsExistsBlock(bk)) { "back must exist" }
        this.fsLoadBlock(bk,null).let { bbk ->
            assert(bbk.immut.time <= blk.immut.time) { "back must be older"}
            when {
                (this.blockState(bbk,blk.immut.time) != State.BLOCKED) -> true
                (blk.immut.prev == null)                                -> false
                else -> this.bfsBacksFindAuthor(blk.sign!!.pub).let {
                    (it!=null && this.blockState(it,blk.immut.time)!=State.BLOCKED)
                }
            }.let {
                assert(it) { "backs must be accepted" }
            }
        }
    }
}

fun Chain.blockAssert (blk: Block) {
    val imm = blk.immut
    //println(">>> ${blk.hash} vs ${imm.toHash()}")
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
            this.repsAuthor(blk.sign!!.pub, imm.time, imm.backs.toList()) >= blk.hash.toHeight().toReps()
        ) {
            "like author must have reputation"
        }
    }
}

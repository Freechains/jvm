package org.freechains.common

import com.goterl.lazycode.lazysodium.LazySodium
import com.goterl.lazycode.lazysodium.interfaces.Sign
import com.goterl.lazycode.lazysodium.utils.Key
import org.freechains.platform.lazySodium
import kotlin.math.sqrt

fun Chain.fromOwner (blk: Block) : Boolean {
    return (this.pub != null) && blk.isFrom(this.pub.key)
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

    val prev = blk.immut.prev
    val ath = when {
        (blk.sign == null) -> 0     // anon post, no author reps
        (prev == null)     -> 0     // no prev post, no author reps
        else -> this.repsAuthor (
            blk.sign.pub,
            this.fsLoadBlock(prev,null).immut.time,
            listOf(prev)
        )
    }
    val reps = this.repsPost(blk.hash)

    return when {
        // unchangeable
        (blk.hash.toHeight() <= 1)  -> State.ACCEPTED      // first two blocks
        this.fromOwner(blk)         -> State.ACCEPTED      // owner signature
        this.trusted                -> State.ACCEPTED      // chain with trusted hosts/authors

        // changeable
        (reps+ath <= 0)             -> State.REJECTED      // not enough reps
        (! hasTime())               -> State.PENDING       // not old enough
        else                        -> State.ACCEPTED      // enough reps, enough time
    }
}

// BLOCK

fun Chain.blockNew (imm_: Immut, sign: HKey?, crypt: HKey?) : Block {
    assert(imm_.prev == null) { "prev must be null" }

    //assert(imm_.backs.isEmpty()) { "backs must be empty" }
    val backs =
        if (!imm_.backs.isEmpty())
            imm_.backs
        else
            this.getHeads(State.ACCEPTED)

    val prev= sign?.let { s ->
        this.bfsFirst(this.heads) { !it.isFrom(s.pvtToPub()) } ?.hash
    }

    val imm = imm_.copy (
        crypt   = (crypt != null),
        payload = if (crypt == null) imm_.payload else imm_.payload.encrypt(crypt),
        prev    = prev,
        backs   = backs
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
    // get old state of liked block
    val wasLiked=
        if (blk.immut.like == null)
            null
        else
            this.blockState(this.fsLoadBlock(blk.immut.like.hash,null))

    this.blockAssert(blk)
    this.fsSaveBlock(blk)

    this.heads.add(blk.hash)
    blk.immut.backs.forEach { this.heads.remove(it) }

    // check if state of liked block changed
    if (wasLiked != null) {
        this.fsLoadBlock(blk.immut.like!!.hash,null).let {
            val now = this.blockState(it)
            //println("${it.hash} : $wasLiked -> $now")
            when {
                // changed from ACC -> REJ
                (wasLiked==State.ACCEPTED && now==State.REJECTED) -> {
                    //println("REJ ${it.hash}")
                    this.blockReject(it.hash)
                    //println(this.heads)
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
        this
            .bfsAll(this.heads)
            .filter { it.hash.toHeight() == 1 }
            .let {
                assert(it.size == 0) { "genesis is already referred" }
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
        this.bfsFirst(this.heads) { !it.isFrom(blk.sign.pub) }.let {
            //println("old = ${it.last()}")
            assert (
                if (it == null)
                    (imm.prev == null)
                else
                    (imm.prev == it.hash)
            ) { "must point to author's previous post" }
        }
    }

    if (imm.like != null) {
        assert(blk.sign != null) { "like must be signed"}
        assert(this.fsExistsBlock(imm.like.hash)) { "like must have valid target" }
        assert (
            this.fromOwner(blk) ||   // owner has infinite reputation
            this.trusted               ||   // dont check reps (private chain)
            this.repsAuthor(blk.sign!!.pub,imm.time) > 0
        ) {
            "like author must have reputation"
        }
    }
}

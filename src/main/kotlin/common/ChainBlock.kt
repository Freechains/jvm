package org.freechains.common

import java.io.File

import com.goterl.lazycode.lazysodium.LazySodium
import com.goterl.lazycode.lazysodium.utils.Key
import org.freechains.platform.lazySodium

fun Chain.isStableRec (blk: Block) : Boolean {
    return this.blockState(blk)==BlockState.ACCEPTED && blk.immut.backs.all {
        this.blockState(this.fsLoadBlock(BlockState.ACCEPTED,it,null)) == BlockState.ACCEPTED
    }
}

fun Chain.blockState (blk: Block) : BlockState {

    // TODO: 2/3
    fun hasReps () : Boolean {
        return blk.sign!=null && this.getPubRep(blk.sign.pub, getNow())>0
    }

    // TODO: sqrt
    fun hasTime () : Boolean {
        return blk.localTime <= getNow()-T2H_past   // old enough
    }

    return when {
        // unchangeable
        blk.immut.height <= 1   -> BlockState.ACCEPTED      // first two blocks
        this.fromOwner(blk)     -> BlockState.ACCEPTED      // owner signature
        blk.immut.like != null  -> BlockState.ACCEPTED      // a like

        // changeable
        ! hasReps()             -> BlockState.REJECTED      // not enough reps
        ! hasTime()             -> BlockState.PENDING       // not old enough
        else                    -> BlockState.ACCEPTED      // enough reps, enough time
    }
}

fun Chain.blockChain (blk: Block) {
    this.fsSaveBlock(blk, BlockState.ACCEPTED)
    this.heads.add(blk.hash)
    this.reBacksFronts(blk)
    this.fsSave()
}

private fun Chain.reBacksFronts (blk: Block) {
    blk.immut.backs.forEach {
        this.heads.remove(it)
        this.fsLoadBlock(BlockState.ACCEPTED,it,null).let {
            assert(!it.fronts.contains(blk.hash)) { it.hash + " -> " + blk.hash }
            it.fronts.add(blk.hash)
            it.fronts.sort()
            this.fsSaveBlock(it, BlockState.ACCEPTED)
        }
    }
}

fun Chain.backsCheck (blk: Block) : Boolean {
    blk.immut.backs.forEach {
        if (! this.fsExistsBlock(BlockState.ACCEPTED,it)) {
            return false        // all backs must exist
        }
        this.fsLoadBlock(BlockState.ACCEPTED,it,null).let {
            if (it.immut.time > blk.immut.time) {
                return false    // all backs must be older
            }
            if (this.blockState(it) != BlockState.ACCEPTED) {
                return false    // all backs must be stable
            }
        }
    }
    return true
}

fun Chain.blockAssert (blk: Block) {
    val imm = blk.immut
    assert(blk.hash == imm.toHash())        // hash matches immut
    assert(this.backsCheck(blk))            // backs exist and are older

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
        val b = this.fsLoadBlock(BlockState.ACCEPTED, gen,null)
        assert(b.fronts.isEmpty() || b.fronts[0]==blk.hash) { "genesis is already referred" }
    }

    if (imm.like != null) {                 // like has reputation
        val n = imm.like.n
        val pub = blk.sign!!.pub
        assert(this.fromOwner(blk) || n <= this.getPubRep(pub, imm.time)) {
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

// FILE SYSTEM

fun Chain.fsSaveBlock (blk: Block, st: BlockState) {
    File(this.root + this.name + st.toDir() + blk.hash + ".blk").writeText(blk.toJson()+"\n")
}

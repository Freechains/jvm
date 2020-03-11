package org.freechains.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import java.io.File

import com.goterl.lazycode.lazysodium.LazySodium
import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import com.goterl.lazycode.lazysodium.interfaces.Sign
import com.goterl.lazycode.lazysodium.utils.Key
import org.freechains.platform.lazySodium
import java.lang.Integer.max
import java.lang.Integer.min
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.absoluteValue

// internal methods are private but are used in tests

@Serializable
data class ChainPub (
    val oonly : Boolean,
    val key   : HKey
)

enum class ChainState {
    WANT, BLOCK, TINE, REM
}

fun ChainState.toDir () : String {
    return when (this) {
        ChainState.BLOCK -> "/blocks/"
        ChainState.TINE  -> "/tines/"
        ChainState.REM   -> "/rems/"
        else -> error("bug found: unexpected ChainState.WANT")
    }
}

fun String.toChainState () : ChainState {
    return when (this) {
        "want"  -> ChainState.WANT
        "block" -> ChainState.BLOCK
        "tine"  -> ChainState.TINE
        "rem"   -> ChainState.REM
        else    -> error("bug found")
    }
}

@Serializable
data class Chain (
    val root   : String,
    val name   : String,
    val pub    : ChainPub?
) {
    val hash   : String = this.toHash()
    val heads  : ArrayList<Hash> = arrayListOf(this.getGenesis())
}

// TODO: change to contract/constructor assertion
fun String.nameCheck () : String {
    assert(this[0]=='/' && (this.length==1 || this.last()!='/')) { "invalid chain: $this"}
    return this
}

// JSON

fun Chain.toJson () : String {
    @UseExperimental(UnstableDefault::class)
    val json = Json(JsonConfiguration(prettyPrint=true))
    return json.stringify(Chain.serializer(), this)
}

fun String.fromJsonToChain () : Chain {
    @UseExperimental(UnstableDefault::class)
    val json = Json(JsonConfiguration(prettyPrint=true))
    return json.parse(Chain.serializer(), this)
}

// GENESIS

fun Chain.getGenesis () : Hash {
    return "0_" + this.toHash()
}

// HASH

val zeros = ByteArray(GenericHash.BYTES)
private fun String.calcHash () : String {
    return lazySodium.cryptoGenericHash(this, Key.fromBytes(zeros))
}

fun Chain.toHash () : String {
    val pub = if (this.pub == null) "" else this.pub.oonly.toString()+"_"+this.pub.key
    return (this.name+pub).calcHash()
}

fun BlockImmut.toHash () : Hash {
    return this.backs.backsToHeight().toString() + "_" + this.toJson().calcHash()
}

// BLOCK

fun Chain.blockNew (imm: BlockImmut, sign: HKey?, crypt: HKey?, acc: Boolean) : Block {
    // non-empty pre-set backs only used in tests
    val backs = if (imm.backs.isNotEmpty()) imm.backs else this.blockHeads(imm)

    val pay = if (crypt == null) imm.payload else imm.payload.encrypt(crypt)

    val h_ = imm.copy(crypt=crypt!=null, payload=pay, backs=backs)
    val hash = h_.toHash()

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

    val new = Block(h_, mutableListOf(), signature, acc, hash)
    this.blockAssert(new)
    this.blockChain(new)
    return new
}

private fun Chain.blockHeads (imm: BlockImmut) : Array<String> {
    // a like must point back to post, this way,
    // if the post is removed, so is the like
    val liked =
        if (imm.like != null && imm.like.ref.hashIsBlock()) {
            val ref = this.fsLoadBlock(ChainState.BLOCK, imm.like.ref,null)
            if (!this.isStable(ref)) {
                return arrayOf(ref.hash)    // liked still to be consolidated, point only to it
            }
            setOf<Hash>(ref.hash)           // liked consolidated, point to it and other heads
        } else {
            emptySet()                      // not a like, point only to heads
        }

    fun dns (hash: Hash) : List<Hash> {
        return this.fsLoadBlock(ChainState.BLOCK,hash,null).let {
            if (this.isStable(it))
                arrayListOf<Hash>(it.hash)
            else
                it.immut.backs.map(::dns).flatten()
        }
    }
    return (liked + this.stableHeads()).toTypedArray()
}

// CHAIN BLOCK

fun Chain.isStableRec (blk: Block) : Boolean {
    return this.isStable(blk) && blk.immut.backs.all {
        this.isStable(this.fsLoadBlock(ChainState.BLOCK, it, null))
    }
}

fun Chain.stableHeads () : List<String> {
    fun dns (hash: Hash) : List<Hash> {
        return this.fsLoadBlock(ChainState.BLOCK,hash,null).let {
            if (this.isStableRec(it))
                arrayListOf<Hash>(it.hash)
            else
                it.immut.backs.map(::dns).flatten()
        }
    }

    val ret = this.heads.toList().map(::dns).flatten().toSet().toList()

    fun isBack (hash: Hash) : Boolean {
        return this.fsLoadBlock(ChainState.BLOCK,hash,null).fronts.any { ret.contains(it) || isBack(it) }
    }

    return ret.filter { !isBack(it) }
}

fun Chain.isStable (blk: Block) : Boolean {
    return (
        // unchangeable
        blk.immut.height <= 1               ||      // first two blocks
        this.fromOwner(blk)                 ||      // owner signature
        blk.immut.like != null              ||      // a like
        // changeable
        blk.accepted                        ||      // manually accepted
        blk.localTime <= getNow()-T2H_past          // old enough (local time)
    )
}

fun Chain.isTine (blk: Block, now: Long) : Boolean {
    return (
        !this.isStable(blk) &&
        (
            blk.immut.time <= now-T2H_past          ||  // too late
            blk.sign == null                        ||  // no sig
            this.getPubRep(blk.sign.pub,now) <= 0       // no rep
        )
    )
}

fun Chain.blockChain (blk: Block) {
    this.fsSaveBlock(ChainState.BLOCK,blk)
    this.heads.add(blk.hash)
    this.reBacksFronts(blk)
    this.fsSave()
}

private fun Chain.reBacksFronts (blk: Block) {
    blk.immut.backs.forEach {
        this.heads.remove(it)
        this.fsLoadBlock(ChainState.BLOCK,it,null).let {
            assert(!it.fronts.contains(blk.hash)) { it.hash + " -> " + blk.hash }
            it.fronts.add(blk.hash)
            it.fronts.sort()
            this.fsSaveBlock(ChainState.BLOCK,it)
        }
    }
}

fun Chain.blockRemove (hash: Hash): Array<Hash> {
    val blk = this.fsLoadBlock(ChainState.BLOCK, hash, null)

    // remove all my fronts as well
    blk.fronts.forEach {
        this.blockRemove(it)
    }

    // reheads: remove myself // add all backs
    if (this.heads.contains(hash)) {
        this.heads.remove(hash)
        blk.immut.backs.forEach {
            assert(!this.heads.contains(it))
            this.heads.add(it)
        }
    }

    // refronts: remove myself as front of all my backs
    blk.immut.backs.forEach {
        this.fsLoadBlock(ChainState.BLOCK, it, null).let {
            it.fronts.remove(hash)
            this.fsSaveBlock(ChainState.BLOCK, it)
        }
    }

    blk.fronts.clear()
    this.fsSaveBlock(ChainState.REM, blk)
    this.fsRemBlock(ChainState.BLOCK, blk.hash)
    this.fsSave()

    return blk.immut.backs
}

fun Chain.backsCheck (blk: Block) : Boolean {
    blk.immut.backs.forEach {
        if (! this.fsExistsBlock(ChainState.BLOCK,it)) {
            return false        // all backs must exist
        }
        this.fsLoadBlock(ChainState.BLOCK,it,null).let {
            if (it.immut.time > blk.immut.time) {
                return false    // all backs must be older
            }
            if (! this.isStable(it)) {
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
        imm.time >= now-T120_past           // not too old
    )

    if (this.pub != null && this.pub.oonly) {
        assert(this.fromOwner(blk))         // signed by owner (if oonly is set)
    }

    val gen = this.getGenesis()      // unique genesis front (unique 1_xxx)
    if (blk.immut.backs.contains(gen)) {
        val b = this.fsLoadBlock(ChainState.BLOCK, gen,null)
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

fun Chain.getPostRep (hash: String) : Int {
    val all = this.traverseFromHeads { true }

    val likes = all
        .filter {
            it.immut.like != null &&
            it.immut.like.ref == hash
        }
        .map { it.immut.like!!.n }
        .sum()

    return likes
}

fun Chain.getPubRep (pub: String, now: Long) : Int {
    val gen = this.fsLoadBlock(ChainState.BLOCK, this.getGenesis(),null).fronts.let {
        if (it.isEmpty())
            LK30_max
        else
            this.fsLoadBlock(ChainState.BLOCK, it[0],null).let {
                when {
                    (it.sign == null) -> 0
                    (it.sign.pub == pub) -> LK30_max
                    else -> 0
                }
            }
    }

    val b90s = this.traverseFromHeads {
        it.immut.time >= now - T90_rep
    }

    val mines = b90s
        .filter { it.sign != null &&
                  it.sign.pub == pub }                       // all I signed

    val (pos,neg) = mines                          // mines
        .filter { it.immut.like == null }                    // not likes
        .let {
            val pos = it
                .filter { it.immut.time <= now - T1D_rep }   // older than 1 day
                .count() * lk
            val neg = it
                .filter { it.immut.time > now - T1D_rep }    // newer than 1 day
                .count() * lk
            Pair(min(LK30_max,pos),neg)
        }

    val gave = mines
        .filter { it.immut.like != null }                    // likes I gave
        .map { it.immut.like!!.n.absoluteValue }
        .sum()

    val got = b90s
        .filter { it.immut.like != null &&                   // likes I got
                it.immut.like.type == LikeType.PUBKEY &&
                it.immut.like.ref == pub }
        .map { it.immut.like!!.n }
        .sum()

    //println("${max(gen,pos)} - $neg + $got - $gave")
    return max(0, max(gen,pos) - neg + got - gave)
}

internal fun Chain.traverseFromHeads (
    heads: List<Hash> = this.heads,
    f: (Block) -> Boolean
) : Array<Block> {
    val pending = LinkedList<String>()
    val visited = mutableSetOf<String>()
    val ret = mutableListOf<Block>()

    for (head in heads) {
        pending.addLast(head)
    }

    while (pending.isNotEmpty()) {
        val hash = pending.removeFirst()
        val blk = this.fsLoadBlock(ChainState.BLOCK, hash,null)
        if (!f(blk)) {
            break
        }
        for (back in blk.immut.backs) {
            if (! visited.contains(back)) {
                visited.add(back)
                pending.addLast(back)
            }
        }
        ret.add(blk)
    }
    return ret.toTypedArray()
}

// FILE SYSTEM

fun Chain.fsSave () {
    val dir = File(this.root + this.name + "/blocks/")
    if (!dir.exists()) {
        dir.mkdirs()
        File(this.root + this.name + "/rems/").mkdirs()
        File(this.root + this.name + "/tines/").mkdirs()
    }
    File(this.root + this.name + "/" + "chain").writeText(this.toJson())
}

fun Chain.fsSaveBlock (st: ChainState, blk: Block) {
    File(this.root + this.name + st.toDir() + blk.hash + ".blk").writeText(blk.toJson()+"\n")
}

fun Chain.fsMoveBlock (from: ChainState, to: ChainState, hash: Hash) {
    File(this.root + this.name + "/" + from.toDir() + "/" + hash + ".blk")
        .renameTo(File(this.root + this.name + "/" + to.toDir() + "/" + hash + ".blk"))
}

fun Chain.fsRemBlock (state: ChainState, hash: Hash) {
    assert(File(this.root + this.name + state.toDir() + hash + ".blk").delete()) { "tine is not found" }
}

fun Chain.fsLoadBlocks (state: ChainState) : List<Hash> {
    return File(this.root + this.name + state.toDir()).list()!!
        .map { it.removeSuffix(".blk") }
}

fun Chain.fsLoadBlock (state: ChainState, hash: Hash, crypt: HKey?) : Block {
    val blk = File(this.root + this.name + state.toDir() + hash + ".blk").readText().jsonToBlock()
    if (crypt==null || !blk.immut.crypt) {
        return blk
    }
    return blk.copy (
        immut = blk.immut.copy (
            crypt   = false,
            payload = blk.immut.payload.decrypt(crypt)
        )
    )
}

fun Chain.fsExistsBlock (state: ChainState, hash: Hash) : Boolean {
    if (this.hash == hash) {
        return true
    } else {
        return File(this.root + this.name + state.toDir() + hash + ".blk").exists()
    }
}

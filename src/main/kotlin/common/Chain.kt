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
            val ref = this.fsLoadBlock(BlockState.ACCEPTED, imm.like.ref,null)
            if (this.blockState(ref) != BlockState.ACCEPTED) {
                return arrayOf(ref.hash)    // liked still to be consolidated, point only to it
            }
            setOf<Hash>(ref.hash)           // liked consolidated, point to it and other heads
        } else {
            emptySet()                      // not a like, point only to heads
        }

    fun dns (hash: Hash) : List<Hash> {
        return this.fsLoadBlock(BlockState.ACCEPTED,hash,null).let {
            if (this.blockState(it) == BlockState.ACCEPTED)
                arrayListOf<Hash>(it.hash)
            else
                it.immut.backs.map(::dns).flatten()
        }
    }
    return (liked + this.stableHeads()).toTypedArray()
}

// CHAIN BLOCK

fun Chain.stableHeads () : List<String> {
    fun dns (hash: Hash) : List<Hash> {
        return this.fsLoadBlock(BlockState.ACCEPTED,hash,null).let {
            if (this.isStableRec(it))
                arrayListOf<Hash>(it.hash)
            else
                it.immut.backs.map(::dns).flatten()
        }
    }

    val ret = this.heads.toList().map(::dns).flatten().toSet().toList()

    fun isBack (hash: Hash) : Boolean {
        return this.fsLoadBlock(BlockState.ACCEPTED,hash,null).fronts.any { ret.contains(it) || isBack(it) }
    }

    return ret.filter { !isBack(it) }
}

fun Chain.blockRemove (hash: Hash): Array<Hash> {
    val blk = this.fsLoadBlock(BlockState.ACCEPTED, hash, null)

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
        this.fsLoadBlock(BlockState.ACCEPTED, it, null).let {
            it.fronts.remove(hash)
            this.fsSaveBlock(it, BlockState.ACCEPTED)
        }
    }

    blk.fronts.clear()
    this.fsSaveBlock(blk, BlockState.BANNED)
    this.fsRemBlock(BlockState.ACCEPTED, blk.hash)
    this.fsSave()

    return blk.immut.backs
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
    val gen = this.fsLoadBlock(BlockState.ACCEPTED, this.getGenesis(),null).fronts.let {
        if (it.isEmpty())
            LK30_max
        else
            this.fsLoadBlock(BlockState.ACCEPTED, it[0],null).let {
                when {
                    (it.sign == null) -> 0
                    (it.sign.pub == pub) -> LK30_max
                    else -> 0
                }
            }
    }

    val b90s = this.traverseFromHeads {
        it.immut.time >= now - T90D_rep
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
        val blk = this.fsLoadBlock(BlockState.ACCEPTED, hash,null)
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
        File(this.root + this.name + "/banned/").mkdirs()
        File(this.root + this.name + "/tines/").mkdirs()
    }
    File(this.root + this.name + "/" + "chain").writeText(this.toJson())
}

fun Chain.fsMoveBlock (from: BlockState, to: BlockState, hash: Hash) {
    File(this.root + this.name + "/" + from.toDir() + "/" + hash + ".blk")
        .renameTo(File(this.root + this.name + "/" + to.toDir() + "/" + hash + ".blk"))
}

fun Chain.fsRemBlock (state: BlockState, hash: Hash) {
    assert(File(this.root + this.name + state.toDir() + hash + ".blk").delete()) { "rejected is not found" }
}

fun Chain.fsLoadBlocks (state: BlockState) : List<Hash> {
    return File(this.root + this.name + state.toDir()).list()!!
        .map { it.removeSuffix(".blk") }
}

fun Chain.fsLoadBlock (state: BlockState, hash: Hash, crypt: HKey?) : Block {
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

fun Chain.fsExistsBlock (state: BlockState, hash: Hash) : Boolean {
    if (this.hash == hash) {
        return true
    } else {
        return File(this.root + this.name + state.toDir() + hash + ".blk").exists()
    }
}

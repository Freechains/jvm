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
    val root    : String,
    val name    : String,
    val trusted : Boolean,
    val pub     : ChainPub?
) {
    val hash    : String = this.toHash()
    val heads   : ArrayList<Hash> = arrayListOf(this.getGenesis())
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

private fun Chain.toHash () : String {
    val pub = if (this.pub == null) "" else this.pub.oonly.toString()+"_"+this.pub.key
    return (this.name+this.trusted.toString()+pub).calcHash()
}

fun Immut.toHash () : Hash {
    fun Array<Hash>.backsToHeight () : Int {
        return when {
            this.isEmpty() -> 0
            else -> 1 + this.map { it.toHeight() }.max()!!
        }
    }
    return this.backs.backsToHeight().toString() + "_" + this.toJson().calcHash()
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

    val imm = imm_.copy (
        crypt   = (crypt != null),
        payload = if (crypt == null) imm_.payload else imm_.payload.encrypt(crypt),
        prev    = if (sign == null) null else this
            .bfsFromHeads(this.heads,true) { !it.isFrom(sign.pvtToPub()) }
            .last().hash,
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

// REPUTATION

fun Chain.repsPost (hash: String) : Pair<Int,Int> {
    val blk = this.fsLoadBlock(hash,null)

    val likes = this
        .bfsFromHeads(this.heads,false) { it.immut.time > blk.immut.time }
        .filter { it.immut.like!=null && it.immut.like.hash==hash }
        .map { it.immut.like!! }

    val pos = likes.filter { it.n > 0 }.map { it.n }.sum()
    val neg = likes.filter { it.n < 0 }.map { it.n }.sum()

    val ath =
        if (blk.sign == null)
            0
        else
            this.repsAuthor (
                blk.sign.pub,
                this.fsLoadBlock(blk.immut.prev!!,null).immut.time,
                listOf(blk.immut.prev)
            )
    //println("${blk.sign!=null} = $ath")

    return Pair(pos+ath,neg)
}

fun Chain.repsPostSum (hash: String) : Int {
    val (pos,neg) = this.repsPost(hash)
    return pos + neg
}

fun Chain.repsAuthor (pub: String, now: Long, heads: List<Hash> = this.heads) : Int {
    val gen = this
        .bfsFromHeads(heads,true) { it.hash.toHeight() > 1 }
        .last()
        .let { if (it.isFrom(pub)) LK30_max else 0 }

    val mines = this
        .bfsFromHeads(heads,true) { !it.isFrom(pub) }
        .last()
        .let {
            fun f (blk: Block) : List<Block> {
                return listOf(blk) + blk.immut.prev.let {
                    if (it == null) emptyList() else f(this.fsLoadBlock(it,null))
                }
            }
            f(it)
        }

    val posts = mines                                   // mines
        .filter { it.immut.like == null }                    // not likes
        .let {
            val lks = it
                .map { this.repsPostSum(it.hash) }
                .sum()                                       // likes to my posts
            val pos = it
                .filter { it.immut.time <= now - T1D_rep }   // posts older than 1 day
                .count()
            val neg = it
                .filter { it.immut.time > now - T1D_rep }    // posts newer than 1 day
                .count()
            lks + max(gen,min(LK30_max,pos)) - neg
        }

    val gave = mines
        .filter { it.immut.like != null }                    // likes I gave
        .map { it.immut.like!!.n.absoluteValue }
        .sum()

    return max(0, posts-gave)
}

// TRAVERSE

fun Chain.getHeads (wanted: State, heads: List<Hash> = this.heads) : Array<Hash> {
    return heads
        .map {
            val have = this.hashState(it)
            when {
                (wanted == have)        -> arrayOf(it)
                (wanted==State.PENDING &&
                 have==State.ACCEPTED)  -> arrayOf(it)
                wanted > have           -> this.getHeads(wanted, this.fsLoadBlock(it,null).immut.backs.toList())
                else                    -> emptyArray()
            }
        }
        .toTypedArray()
        .flatten()
        .toTypedArray()
}

fun Chain.bfsFromHeads (heads: List<Hash>, inc: Boolean, f: (Block) -> Boolean) : Array<Block> {
    val pending = LinkedList<String>()
    val visited = mutableSetOf<String>()
    val ret = mutableListOf<Block>()

    for (head in heads) {
        pending.addLast(head)
    }

    while (pending.isNotEmpty()) {
        val hash = pending.removeFirst()
        val blk = this.fsLoadBlock(hash, null)
        if (!f(blk)) {
            if (inc) {
                ret.add(blk)
            }
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
    }
    File(this.root + this.name + "/" + "chain").writeText(this.toJson())
}

fun Chain.fsRemBlock (hash: Hash, dir: String="/blocks/") {
    assert(File(this.root + this.name + dir + hash + ".blk").delete()) { "rejected is not found" }
}

fun Chain.fsLoadBlock (hash: Hash, crypt: HKey?, dir: String="/blocks/") : Block {
    val blk = File(this.root + this.name + dir + hash + ".blk").readText().jsonToBlock()
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

fun Chain.fsExistsBlock (hash: Hash, dir: String ="/blocks/") : Boolean {
    if (this.hash == hash) {
        return true
    } else {
        return File(this.root + this.name + dir + hash + ".blk").exists()
    }
}

fun Chain.fsSaveBlock (blk: Block, dir: String="/blocks/") {
    File(this.root + this.name + dir + blk.hash + ".blk").writeText(blk.toJson()+"\n")
}

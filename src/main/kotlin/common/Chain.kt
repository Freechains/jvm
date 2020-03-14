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

private fun Chain.toHash () : String {
    val pub = if (this.pub == null) "" else this.pub.oonly.toString()+"_"+this.pub.key
    return (this.name+pub).calcHash()
}

fun Immut.toHash () : Hash {
    return this.backs.backsToHeight().toString() + "_" + this.toJson().calcHash()
}

// BLOCK

fun Chain.blockNew (imm: Immut, sign: HKey?, crypt: HKey?, acc: Boolean) : Block {
    val heads = this.getHeads(State.ACCEPTED)
    val backs = when {
        imm.backs.isNotEmpty() -> imm.backs    // used in tests and likes
        imm.isLikeBlock()      -> arrayOf(imm.like!!.ref)
        else                   -> heads.toTypedArray()
    }

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

// HEADS

fun Chain.getHeads (state: State) : List<Hash> {
    fun recs (state: State, hs: List<Hash>) : List<Hash> {
        return hs
            .map {
                this.fsLoadBlock(it,null).let {
                    //println("${it.hash} -> ${this.blockState(it)}")
                    when {
                        // if like block, go back until finds liked block
                        it.immut.isLikeBlock()         -> recs(state, it.immut.backs.toList())

                        // block has expected state, return it
                        (this.blockState(it) == state) -> listOf(it.hash)

                        // I want accepted blocks, go back to find them
                        (state == State.ACCEPTED)      -> recs(state, it.immut.backs.toList())

                        // did not find
                        else                           -> emptyList()
                    }
                }
            }
            .flatten()
    }
    val ret = recs(state, this.heads)

    // if accepted, go to the tip accepteds in fronts
    fun fronts (hs: List<Hash>) : List<Hash> {
        return hs
            .map    { this.fsLoadBlock(it,null) }
            .filter { this.blockState(it) == State.ACCEPTED }
            .map    {
                val blk = it
                fronts(blk.fronts).let {
                    if (it.isEmpty()) listOf(blk.hash) else it
                }
            }
            .flatten()
    }

    return if (state != State.ACCEPTED) ret else fronts(ret).toSet().toList()
}

// REMOVE

fun Chain.blockBan (hash: Hash): Array<Hash> {
    val blk = this.fsLoadBlock(hash, null)

    // remove all my fronts as well
    blk.fronts.forEach {
        this.blockBan(it)
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
        this.fsLoadBlock(it, null).let {
            it.fronts.remove(hash)
            this.fsSaveBlock(it)
        }
    }

    blk.fronts.clear()
    this.fsSaveBlock(blk, "/banned/")
    this.fsRemBlock(blk.hash)
    this.fsSave()

    return blk.immut.backs
}

// REPUTATION

fun Chain.repsPost (hash: String) : Pair<Int,Int> {
    val likes = this.fsLoadBlock(hash,null)
        .fronts
        .map { this.fsLoadBlock(it,null) }
        .filter {
            it.immut.like != null &&
            it.immut.like.ref == hash
        }
        .map { it.immut.like!! }
    val pos = likes.filter { it.n > 0 }.map { it.n }.sum()
    val neg = likes.filter { it.n < 0 }.map { it.n }.sum()
    return Pair(pos,neg)
}

fun Chain.repsPostSum (hash: String) : Int {
    val (pos,neg) = this.repsPost(hash)
    return pos + neg
}

fun Chain.repsPub (pub: String, now: Long) : Int {
    val gen = this.fsLoadBlock(this.getGenesis(), null).fronts.let {
        if (it.isEmpty())
            LK30_max
        else
            this.fsLoadBlock(it[0], null).let {
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

// TRAVERSE
// TODO: State

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
        val blk = this.fsLoadBlock(hash, null)
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
    }
    File(this.root + this.name + "/" + "chain").writeText(this.toJson())
}

fun Chain.fsRemBlock (hash: Hash) {
    assert(File(this.root + this.name + "/blocks/" + hash + ".blk").delete()) { "rejected is not found" }
}

fun Chain.fsLoadBlock (hash: Hash, crypt: HKey?) : Block {
    val blk = File(this.root + this.name + "/blocks/" + hash + ".blk").readText().jsonToBlock()
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

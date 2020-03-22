package org.freechains.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import java.io.File

import com.goterl.lazycode.lazysodium.interfaces.GenericHash
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
    val pub = this.pub?.let { it.oonly.toString()+"_"+it.key } ?: ""
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

// TRAVERSE

fun Chain.getHeads (want: State, heads: List<Hash> = this.heads) : Array<Hash> {
    return heads
        .map {
            val have = this.hashState(it)
            when {
                (want == have)         -> arrayOf(it)
                (want==State.PENDING &&
                 have==State.ACCEPTED) -> arrayOf(it)
                want > have            -> this.getHeads(want, this.fsLoadBlock(it,null).immut.backs.toList())
                else                   -> emptyArray()
            }
        }
        .toTypedArray()
        .flatten()
        .let { ret ->
            ret.filter { cur ->
                // I tried all heads and none of their backs lead to cur
                // so cur is a head
                ret.none {
                    val x = this.fsLoadBlock(it,null).immut.backs.toList()
                    (x.isNotEmpty() && this.isBack(x,cur))
                }
            }
        }
        .toSet()
        .toTypedArray()
}

fun Chain.isBack (heads: List<Hash>, hash: Hash) : Boolean {
    return this.bfsFirst(heads) { it.hash != hash } != null
}

fun Chain.bfsFirst (heads: List<Hash>, pred: (Block) -> Boolean) : Block? {
    return this
        .bfs(heads,true, pred)
        .last()
        .let {
            if (it.hash == this.getGenesis())
                null
            else
                it
        }
}

fun Chain.bfsAll (heads: List<Hash>) : Array<Block> {
    return this.bfs(heads,false) { true }
}

fun Chain.bfs (heads: List<Hash>, inc: Boolean, f: (Block) -> Boolean) : Array<Block> {
    val ret = mutableListOf<Block>()
    val pending = TreeSet<Block>(compareByDescending { it.immut.time })
    pending.addAll(heads.map { this.fsLoadBlock(it,null) })

    while (pending.isNotEmpty()) {
        val blk = pending.first()
        pending.remove(blk)
        if (!f(blk)) {
            if (inc) {
                ret.add(blk)
            }
            break
        }

        pending.addAll(blk.immut.backs.map { this.fsLoadBlock(it,null) })
        ret.add(blk)
    }

    return ret.toTypedArray()
}

// REPUTATION

fun Chain.repsPost (hash: String) : Int {
    val blk = this.fsLoadBlock(hash,null)

    val likes = this
        .bfs(this.heads,false) { it.immut.time > blk.immut.time }
        .filter { it.immut.like!=null && it.immut.like.hash==hash }
        .map { it.immut.like!! }

    val pos = likes.filter { it.n > 0 }.map { it.n }.sum()
    val neg = likes.filter { it.n < 0 }.map { it.n }.sum()

    //println("$hash // pos=$pos // neg=$neg")
    return pos + neg
}

fun Chain.repsAuthor (pub: String, now: Long, heads: List<Hash> = this.heads) : Int {
    val gen = this.bfsFirst(heads) { it.hash.toHeight() > 1 }.let {
        when {
            (it == null)   -> 0
            it.isFrom(pub) -> LK30_max
            else           -> 0
        }
    }

    val mines = this
        .bfsFirst(heads) { !it.isFrom(pub) }.let { blk ->
            if (blk == null) {
                emptyList()
            } else {
                fun f (blk: Block) : List<Block> {
                    return listOf(blk) + blk.immut.prev.let {
                        if (it == null) emptyList() else f(this.fsLoadBlock(it,null))
                    }
                }
                f(blk)
            }
        }

    val posts = mines                                   // mines
        .filter { it.immut.like == null }                    // not likes
        .let { list ->
            val pos = list
                .filter { it.immut.time <= now - T1D_rep }   // posts older than 1 day
                .count()
            val neg = list
                .filter { it.immut.time > now - T1D_rep }    // posts newer than 1 day
                .count()
            //println("gen=$gen // pos=$pos // neg=$neg // now=$now")
            max(gen,min(LK30_max,pos)) - neg
        }

    val recv = mines
        .map { this.repsPost(it.hash) }
        .sum()                                               // likes I received

    val gave = mines
        .filter { it.immut.like != null }                    // likes I gave
        //.let { println(it) ; it }
        .map { it.immut.like!!.n.absoluteValue }
        .sum()

    //println("gave=$gave // recv=$recv")
    return posts + recv - gave
}

// FILE SYSTEM

fun Chain.fsSave () {
    val dir = File(this.root + this.name + "/blocks/")
    if (!dir.exists()) {
        dir.mkdirs()
        File(this.root + this.name + "/rems/").mkdirs()
        File(this.root + this.name + "/bans/").mkdirs()
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
    return (this.hash == hash) ||
           File(this.root + this.name + dir + hash + ".blk").exists()
}

fun Chain.fsSaveBlock (blk: Block, dir: String="/blocks/") {
    File(this.root + this.name + dir + blk.hash + ".blk").writeText(blk.toJson()+"\n")
}

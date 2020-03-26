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

fun Chain.getHeads (want: State, hash: Hash = this.getGenesis()) : List<Hash> {
    val have = if (want == State.ALL) null else this.hashState(hash)

    val rec = this.fsLoadBlock(hash, null)
        .fronts
        .map { this.getHeads(want, it) }
        .flatten()
        .toSet()
        .toList()

    fun f (hash: Hash, list: List<Hash>) : List<Hash> {
        return if (list.isEmpty()) listOf(hash) else list
    }

    return when (want) {
        State.ALL              -> f(hash, rec)
        State.REJECTED ->
            when (have!!) {
                State.REJECTED -> listOf(hash)
                else           -> rec
            }
        State.ACCEPTED ->
            when (have!!) {
                State.ACCEPTED -> f(hash, rec)
                else           -> emptyList()
            }
        State.PENDING ->
            when (have!!) {
                State.ACCEPTED -> f(hash, rec)
                State.PENDING  -> f(hash, rec)
                else           -> emptyList()
            }
        else -> error("impossible case")
    }
}

fun Chain.bfsFrontsIsFromTo (from: Hash, to: Hash) : Boolean {
    //println(this.bfsFirst(listOf(from), true) { it.hash == to })
    return to == (this.bfsFrontsFirst(from) { it.hash == to }!!.hash)
}

fun Chain.bfsBacksFindAuthor (pub: String) : Block? {
    return this.bfsBacksFirst(this.getHeads(State.ALL)) { it.isFrom(pub) }
}

fun Chain.bfsFrontsFirst (start: Hash, pred: (Block) -> Boolean) : Block? {
    return this.bfsFirst(listOf(start), true, pred)
}

fun Chain.bfsBacksFirst (starts: List<Hash>, pred: (Block) -> Boolean) : Block? {
    return this.bfsFirst(starts, false, pred)
}

private fun Chain.bfsFirst (starts: List<Hash>, fromGen: Boolean, pred: (Block) -> Boolean) : Block? {
    return this
        .bfs(starts,true, fromGen) { !pred(it) }
        .last()
        .let {
            if (it.hash == this.getGenesis())
                null
            else
                it
        }
}

fun Chain.bfsAll (start: Hash = this.getGenesis()) : List<Block> {
    return this.bfsFronts(start,false) { true }
}

fun Chain.bfsFronts (start: Hash, inc: Boolean, ok: (Block) -> Boolean) : List<Block> {
    return this.bfs(listOf(start), inc, true, ok)
}

fun Chain.bfsBacks (starts: List<Hash>, inc: Boolean, ok: (Block) -> Boolean) : List<Block> {
    return this.bfs(starts, inc, false, ok)
}

internal fun Chain.bfs (starts: List<Hash>, inc: Boolean, fromGen: Boolean, ok: (Block) -> Boolean) : List<Block> {
    val ret = mutableListOf<Block>()

    val pending =
        if (fromGen) {
            TreeSet<Block>(compareBy { it.immut.time })
        } else {
            TreeSet<Block>(compareByDescending { it.immut.time })       // TODO: val cmp = ...
        }
    pending.addAll(starts.map { this.fsLoadBlock(it,null) })

    val visited = starts.toMutableSet()

    while (pending.isNotEmpty()) {
        val blk = pending.first()
        pending.remove(blk)
        if (!ok(blk)) {
            if (inc) {
                ret.add(blk)
            }
            break
        }

        val list = if (fromGen) blk.fronts else blk.immut.backs.toList()
        pending.addAll(list.minus(visited).map { this.fsLoadBlock(it,null) })
        visited.addAll(list)
        ret.add(blk)
    }

    return ret
}

// REPUTATION

fun Chain.repsPost (hash: String, chkRejected: Boolean) : Int {
    val blk = this.fsLoadBlock(hash,null)

    val iReach = if (chkRejected) this.bfsAll(hash) else emptyList()

    val likes = this
        .bfsAll ()
        .filter { x -> iReach.none { y -> x.hash == y.hash } } // remove likes reached from hash itself
        .filter { it.immut.like!=null && it.immut.like.hash==hash }
        .filter { !chkRejected || blk.immut.time > (it.immut.time-T1D_rep_eng) }
        .map    { it.immut.like!! }

    val pos = likes.filter { it.n > 0 }.map { it.n }.sum()
    val neg = likes.filter { it.n < 0 }.map { it.n }.sum()

    //println("$hash // chk=$chkRejected // pos=$pos // neg=$neg")
    return pos + neg
}

fun Chain.repsAuthor (pub: String, now: Long, heads: List<Hash> = this.getHeads(State.ALL).toList()) : Int {
    val gen = this.bfsFrontsFirst(this.getGenesis()) { it.hash.toHeight() >= 1 }.let {
        when {
            (it == null)   -> 0
            it.isFrom(pub) -> LK30_max
            else           -> 0
        }
    }

    val mines = this
        .bfsBacksFirst(heads) { it.isFrom(pub) }.let { blk ->
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
                .filter { it.immut.time <= now - T1D_rep_eng }   // posts older than 1 day
                .count()
            val neg = list
                .filter { it.immut.time > now - T1D_rep_eng }    // posts newer than 1 day
                .count()
            //println("gen=$gen // pos=$pos // neg=$neg // now=$now")
            max(gen,min(LK30_max,pos)) - neg
        }

    val recv = mines
        .map { this.repsPost(it.hash,false) }
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

internal fun Chain.fsSave () {
    val dir = File(this.root + this.name + "/blocks/")
    if (!dir.exists()) {
        dir.mkdirs()
        File(this.root + this.name + "/bans/").mkdirs()
    }
    File(this.root + this.name + "/" + "chain").writeText(this.toJson())
}

fun Chain.fsRemBlock (hash: Hash, dir: String="/blocks/") {
    assert(File(this.root + this.name + dir + hash + ".blk").delete()) { "block is not found" }
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

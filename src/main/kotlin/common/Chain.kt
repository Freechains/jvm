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

// HEADS

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

// REPUTATION

fun Chain.repsPost (hash: String, isAll: Boolean) : Int {
    val blk = this.fsLoadBlock(hash,null)

    val iReach = if (isAll) emptyList() else this.bfsFrontsAll(hash)

    val likes = this
        .bfsFrontsAll ()
        .filter { x -> iReach.none { y -> x.hash == y.hash } } // remove likes reached from hash itself
        .filter { it.immut.like!=null && it.immut.like.hash==hash }
        .filter { isAll || blk.immut.time > (it.immut.time-T1D_rep_eng) }
        .map    { it.immut.like!! }

    val pos = likes.filter { it.n > 0 }.map { it.n }.sum()
    val neg = likes.filter { it.n < 0 }.map { it.n }.sum()

    //println("$hash // chk=$chkRejected // pos=$pos // neg=$neg")
    return pos + neg
}

fun Chain.repsAuthor (pub: String, now: Long, heads: List<Hash>) : Int {
    val gen = this.bfsFrontsFirst(this.getGenesis()) { it.hash.toHeight() >= 1 }.let {
        when {
            (it == null)   -> 0
            it.isFrom(pub) -> LK30_max
            else           -> 0
        }
    }

    val mines = this.bfsBacksAuthor(heads,pub)

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

    val recv = this.bfsBacksAll(heads) // all pointing to heads
        .filter { it.immut.like != null }   // which are likes
        .filter {                           // which are likes to me
            this.fsLoadBlock(it.immut.like!!.hash,null).let {
                (it.sign!=null && it.sign.pub==pub)
            }
        }
        .map    { it.immut.like!!.n }       // get likes N
        .sum()                              // likes I received

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

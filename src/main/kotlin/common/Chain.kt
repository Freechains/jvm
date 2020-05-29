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
import kotlin.math.ceil

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
    val path    : String = this.root + "/" + this.name.replace('/','_')
    val hash    : String = this.toHash()
    val heads   : ArrayList<Hash> = arrayListOf(this.getGenesis())
}

// TODO: change to contract/constructor assertion
fun String.nameCheck () : String {
    assert (
        this.startsWith('/') &&
        (this.length==1 || !this.endsWith('/')) &&
        !this.contains('_')
    ) { "invalid chain: $this"}
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
fun String.calcHash () : String {
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

fun Chain.setHeads (news: List<Hash>) {
    this.heads.clear()
    this.heads.addAll(this.bfsCleanHeads(news))
}

fun Chain.getHeads (want: State) : List<Hash> {
    val now = getNow()

    fun aux (hash: Hash) : List<Hash> {
        val blk = this.fsLoadBlock(hash)
        val state = this.blockState(blk,now)
        return when (want) {
            State.ALL     -> listOf(hash)
            State.LINKED  -> if (state >  State.LINKED)  listOf(hash) else blk.immut.backs.map(::aux).flatten()
            State.BLOCKED -> if (state == State.BLOCKED) listOf(hash) else emptyList()
            else -> error("impossible case")
        }
    }

    return this.heads
        .map (::aux)
        .flatten ()
        .let { this.bfsCleanHeads(it) }
}

// REPUTATION

fun Int.toReps () : Int {
    return ceil(this.toFloat() / 10).toInt()
}

fun Chain.repsPost (hash: String) : Pair<Int,Int> {
    val likes = this
        .bfsFrontsAll(hash)
        .filter { it.immut.like != null }           // only likes
        .filter { it.immut.like!!.hash == hash }    // only likes to this post
        .map    { it.immut.like!!.n * it.hash.toHeight().toReps() }

    val pos = likes.filter { it > 0 }.map { it }.sum()
    val neg = likes.filter { it < 0 }.map { it }.sum()

    //println("$hash // chk=$chkRejected // pos=$pos // neg=$neg")
    return Pair(pos,-neg)
}

fun Chain.repsAuthor (pub: String, now: Long, heads: List<Hash>) : Int {
    val gen = this.fsLoadBlock(this.getGenesis()).fronts.let {
        when {
            it.isEmpty() -> 0
            this.fsLoadBlock(it.first()).isFrom((pub)) -> LK30_max
            else         -> 0
        }
    }

    val mines = this.bfsBacksAuthor(heads,pub)

    val posts = mines                                    // mines
        .filter { it.immut.like == null }                     // not likes
        .let { list ->
            val pos = list
                .filter { now >= it.immut.time + T1D_reps }   // posts older than 1 day
                .map    { it.hash.toHeight().toReps() }       // get reps of each post height
                .sum    ()                                    // sum everything
            val neg = list
                .filter { now <  it.immut.time + T1D_reps }   // posts newer than 1 day
                .map    { it.hash.toHeight().toReps() }       // get reps of each post height
                .sum    ()                                    // sum everything
            //println("gen=$gen // pos=$pos // neg=$neg // now=$now")
            max(gen,min(LK30_max,pos)) - neg
        }

    val recv = this.bfsBacksAll(heads)                     // all pointing from heads to genesis
        .filter { it.immut.like != null }                       // which are likes
        .filter {                                               // and are to me
            this.fsLoadBlock(it.immut.like!!.hash).let {
                (it.sign!=null && it.sign.pub==pub)
            }
        }
        .map    { it.immut.like!!.n * it.hash.toHeight().toReps() } // get likes N
        .sum()                                                      // likes I received

    val gave = mines
        .filter { it.immut.like != null }                       // likes I gave
        //.let { println(it) ; it }
        .map { it.hash.toHeight().toReps() }                    // doesn't matter the signal
        .sum()

    //println("posts=$posts + recv=$recv - gave=$gave")
    return posts + recv - gave
}

// FILE SYSTEM

internal fun Chain.fsSave () {
    val dir = File(this.path + "/blocks/")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    File(this.path + "/" + "chain").writeText(this.toJson())
}

fun Chain.fsLoadBlock (hash: Hash) : Block {
    return File(this.path + "/blocks/" + hash + ".blk").readText().jsonToBlock()
}

fun Chain.fsLoadPay (hash: Hash, crypt: HKey?) : String {
    val blk = this.fsLoadBlock(hash)
    val pay = File(this.path + "/blocks/" + hash + ".pay").readBytes().toString(Charsets.UTF_8)
    if (crypt==null || !blk.immut.pay.crypt) {
        return pay
    }
    return pay.decrypt(crypt)
}

fun Chain.fsExistsBlock (hash: Hash) : Boolean {
    return (this.hash == hash) ||
           File(this.path + "/blocks/" + hash + ".blk").exists()
}

fun Chain.fsSaveBlock (blk: Block) {
    File(this.path + "/blocks/" + blk.hash + ".blk").writeText(blk.toJson()+"\n")
}

fun Chain.fsSavePay (hash: Hash, pay: String) {
    File(this.path + "/blocks/" + hash + ".pay").writeText(pay)
}

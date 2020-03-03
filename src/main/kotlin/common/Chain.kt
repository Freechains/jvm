package org.freechains.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import java.io.File

import com.goterl.lazycode.lazysodium.LazySodium
import com.goterl.lazycode.lazysodium.interfaces.Box
import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import com.goterl.lazycode.lazysodium.interfaces.SecretBox
import com.goterl.lazycode.lazysodium.interfaces.Sign
import com.goterl.lazycode.lazysodium.utils.Key
import org.freechains.platform.lazySodium
import java.lang.Integer.max
import java.lang.Integer.min
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.absoluteValue

// internal methods are private but are used in tests

typealias HKey = String

@Serializable
sealed class Crypto

@Serializable
data class Shared (
    val key : HKey?
) : Crypto()

@Serializable
data class PubPvt (
    val oonly : Boolean,
    val pub   : HKey,
    val pvt   : HKey?
) : Crypto()

@Serializable
data class Chain (
    val root   : String,
    val name   : String,
    val crypto : Crypto?
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

fun Chain.isSharedWithKey () : Boolean {
    return (this.crypto is Shared && this.crypto.key!=null)
}

// HASH

val zeros = ByteArray(GenericHash.BYTES)
private fun String.calcHash () : String {
    return lazySodium.cryptoGenericHash(this, Key.fromBytes(zeros))
}

fun Chain.toHash () : String {
    val crypto = when (this.crypto) {
        null      -> ""
        is Shared -> ""     // no key: allows untrusted nodes
        is PubPvt -> this.crypto.oonly.toString() + this.crypto.pub
    }
    return (this.name+crypto).calcHash()
}

fun BlockHashable.toHash () : Hash {
    return this.backs.backsToHeight().toString() + "_" + this.toJson().calcHash()
}

// NODE

fun Chain.blockNew (sig_pvt: String?, h: BlockHashable) : Block {
    // non-empty pre-set backs only used in tests
    val backs = if (h.backs.isNotEmpty()) h.backs else this.heads.toTypedArray()

    assert(this.crypto !is Shared || h.encrypted)
    val pay = if (h.encrypted) this.encrypt(h.payload) else h.payload

    val h_ = h.copy(payload=pay, backs=backs)
    val hash = h_.toHash()

    // signs message if requested (pvt provided or in pvt chain)
    val signature= if (sig_pvt == null) null else {
        val pvt = if (sig_pvt != "chain") sig_pvt else (this.crypto as PubPvt).pvt!!
        val sig = ByteArray(Sign.BYTES)
        val msg = lazySodium.bytes(hash)
        val key = Key.fromHexString(pvt).asBytes
        lazySodium.cryptoSignDetached(sig, msg, msg.size.toLong(), key)
        val sig_hash = LazySodium.toHex(sig)
        Signature(sig_hash, pvt.pvtToPub())
    }

    val new = Block(h_, mutableListOf(), signature, hash)
    this.blockChain(new)
    return new
}

// CHAIN BLOCK

fun Chain.blockChain (blk: Block, asr: Boolean = true) {
    if (asr) {
        this.blockAssert(blk)       // skip for testing purposes
    }
    this.saveBlock(blk)
    this.reheads(blk)
    this.save()
}

fun Chain.backsCheck (blk: Block) : Boolean {
    for (back in blk.hashable.backs) {
        if (! this.containsBlock(back)) {
            return false
        }
        val bk = this.loadBlock(back,false)
        if (bk.hashable.time > blk.hashable.time) {
            return false
        }
    }
    return true
}

fun Chain.blockAssert (blk: Block) {
    val h = blk.hashable
    assert(blk.hash == h.toHash())

    assert(this.backsCheck(blk))

    // checks if unique genesis front
    val gen = this.getGenesis()
    if (blk.hashable.backs.contains(gen)) {
        val b = this.loadBlock(gen,false)
        assert(b.fronts.isEmpty() || b.fronts[0]==blk.hash) { "genesis is already referred" }
    }

    // checks if has enough reputation to like
    if (h.like != null) {
        val n = h.like.n
        val pub = blk.signature!!.pub
        assert(this.fromOwner(blk) || n <= this.getPubRep(pub, h.time)) {
            "not enough reputation"
        }
    }

    // checks if sig.hash/blk.hash/sig.pubkey match
    if (blk.signature != null) {
        val sig = LazySodium.toBin(blk.signature.hash)
        val msg = lazySodium.bytes(blk.hash)
        val key = Key.fromHexString(blk.signature.pub).asBytes
        assert(lazySodium.cryptoSignVerifyDetached(sig, msg, msg.size, key)) { "invalid signature" }
    }
}

private fun Chain.reheads (blk: Block) {
    this.heads.add(blk.hash)
    for (back in blk.hashable.backs) {
        this.heads.remove(back)
        val bk = this.loadBlock(back,false)
        assert(!bk.fronts.contains(blk.hash))
        bk.fronts.add(blk.hash)
        bk.fronts.sort()
        this.saveBlock(bk)
    }
}

// CRYPTO

private fun Chain.encrypt (payload: String) : String {
    return when (this.crypto) {
        is Shared -> {
            val nonce = lazySodium.nonce(SecretBox.NONCEBYTES)
            val key = Key.fromHexString(this.crypto.key)
            LazySodium.toHex(nonce) + lazySodium.cryptoSecretBoxEasy(payload, nonce, key)
        }
        is PubPvt -> {
            val dec = payload.toByteArray()
            val enc = ByteArray(Box.SEALBYTES + dec.size)
            val key = Key.fromHexString(this.crypto.pub).asBytes
            val key_ = ByteArray(Box.CURVE25519XSALSA20POLY1305_PUBLICKEYBYTES)
            assert(lazySodium.convertPublicKeyEd25519ToCurve25519(key_, key))
            lazySodium.cryptoBoxSeal(enc, dec, dec.size.toLong(), key_)
            LazySodium.toHex(enc)
        }
        else -> error("bug found")
    }
}

private fun Chain.decrypt (payload: String) : Pair<Boolean,String> {
    return when (this.crypto) {
        null -> Pair(false,payload)
        is Shared -> {
            val idx = SecretBox.NONCEBYTES * 2
            val pay = lazySodium.cryptoSecretBoxOpenEasy(
                payload.substring(idx),
                LazySodium.toBin(payload.substring(0, idx)),
                Key.fromHexString(this.crypto.key)
            )
            Pair(true,pay)
        }
        is PubPvt -> {
            val enc = LazySodium.toBin(payload)
            val dec = ByteArray(enc.size - Box.SEALBYTES)

            val pub = Key.fromHexString(this.crypto.pub).asBytes
            val pvt = Key.fromHexString(this.crypto.pvt).asBytes
            val pub_ = ByteArray(Box.CURVE25519XSALSA20POLY1305_PUBLICKEYBYTES)
            val pvt_ = ByteArray(Box.CURVE25519XSALSA20POLY1305_SECRETKEYBYTES)
            assert(lazySodium.convertPublicKeyEd25519ToCurve25519(pub_,pub))
            assert(lazySodium.convertSecretKeyEd25519ToCurve25519(pvt_,pvt))

            assert(lazySodium.cryptoBoxSealOpen(dec, enc, enc.size.toLong(), pub_, pvt_))
            Pair(true,dec.toString(Charsets.UTF_8))
        }
    }
}

// LIKE

fun Chain.fromOwner (blk: Block) : Boolean {
    return when (this.crypto) {
        is PubPvt -> blk.signature!=null && blk.signature.pub==this.crypto.pub
        else -> false
    }
}

fun Chain.getPostRep (hash: String) : Int {
    val all = this.traverseFromHeads { true }

    val likes = all
        .filter {
            it.hashable.like != null &&
            it.hashable.like.ref == hash
        }
        .map { it.hashable.like!!.n }
        .sum()

    return likes
}

fun Chain.getPubRep (pub: String, now: Long) : Int {
    val gen = this.loadBlock(this.getGenesis(),false).fronts.let {
        if (it.isEmpty())
            LK30_max
        else
            this.loadBlock(it[0],false).let {
                when {
                    (it.signature == null) -> 0
                    (it.signature.pub == pub) -> LK30_max
                    else -> 0
                }
            }
    }

    val b90s = this.traverseFromHeads {
        it.hashable.time >= now - T90_rep
    }

    val mines = b90s
        .filter { it.signature != null &&
                it.signature.pub == pub }                       // all I signed

    val (pos,neg) = mines                             // mines
        .filter { it.hashable.like == null }                    // not likes
        .let {
            val pos = it
                .filter { it.hashable.time <= now - 1*day }     // older than 1 day
                .count() * lk
            val neg = it
                .filter { it.hashable.time > now - 1*day }      // newer than 1 day
                .count() * lk
            Pair(min(LK30_max,pos),neg)
        }

    val gave = mines
        .filter { it.hashable.like != null }                    // likes I gave
        .map { it.hashable.like!!.n.absoluteValue }
        .sum()

    val got = b90s
        .filter { it.hashable.like != null &&                   // likes I got
                it.hashable.like.type == LikeType.PUBKEY &&
                it.hashable.like.ref == pub }
        .map { it.hashable.like!!.n }
        .sum()

    //println("${max(gen,pos)} - $neg + $got - $gave")
    return max(gen,pos) - neg + got - gave
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
        val blk = this.loadBlock(hash,false)
        if (!f(blk)) {
            break
        }
        for (back in blk.hashable.backs) {
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

fun Chain.save () {
    val dir = File(this.root + this.name + "/blocks/")
    if (!dir.exists()) {
        dir.mkdirs()
        File(this.root + this.name + "/tines/").mkdirs()
    }
    File(this.root + this.name + "/" + "chain").writeText(this.toJson())
}

// TINE

fun Chain.saveTine (blk: Block) {
    File(this.root + this.name + "/tines/" + blk.hash + ".blk").writeText(blk.toJson()+"\n")
}

fun Chain.delTine (blk: Block) {
    File(this.root + this.name + "/tines/" + blk.hash + ".blk").delete()
}

fun Chain.loadTines () : List<Hash> {
    return File(this.root + this.name + "/tines/").list()!!
        .map { it.removeSuffix(".blk") }
}

fun Chain.loadTine (hash: Hash) : Block {
    val blk = File(this.root + this.name + "/tines/" + hash + ".blk").readText().jsonToBlock()
    return blk
}

fun Chain.containsTine (hash: Hash) : Boolean {
    return File(this.root + this.name + "/tines/" + hash + ".blk").exists()
}

// BLOCK

fun Chain.saveBlock (blk: Block) {
    File(this.root + this.name + "/blocks/" + blk.hash + ".blk").writeText(blk.toJson()+"\n")
}

fun Chain.loadBlock (hash: Hash, decrypt: Boolean) : Block {
    val blk = File(this.root + this.name + "/blocks/" + hash + ".blk").readText().jsonToBlock()
    if (!decrypt || !blk.hashable.encrypted) {
        return blk
    }
    val (succ,pay) =
        if (blk.hashable.encrypted)
            this.decrypt(blk.hashable.payload)
        else
            Pair(true,blk.hashable.payload)
    return blk.copy(hashable = blk.hashable.copy(encrypted=!succ, payload=pay))
}

fun Chain.containsBlock (hash: Hash) : Boolean {
    if (this.hash == hash) {
        return true
    } else {
        return File(this.root + this.name + "/blocks/" + hash + ".blk").exists()
    }
}

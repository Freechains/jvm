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
import java.lang.Integer.min
import java.util.*
import kotlin.collections.ArrayList

@Serializable
data class Chain (
    val root  : String,
    val name  : String,
    val ro    : Boolean,
    val keys  : Array<String>   // [shared,public,private]
) {
    val hash  : String = this.toHash()
    val heads : ArrayList<Hash> = arrayListOf(this.getGenesis())
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
    return (this.name+this.ro.toString()+this.keys[1]).calcHash() // no shared/private allows untrusted nodes
}

fun BlockHashable.toHash () : Hash {
    return this.backs.backsToHeight().toString() + "_" + this.toJson().calcHash()
}

// NODE

fun Chain.newBlock (sig_pvt: String, now: Long, h: BlockHashable) : Block {
    // non-empty pre-set backs only used in tests
    val backs = if (h.backs.isNotEmpty()) h.backs else this.decideBacks(h)

    val pay = if (h.encrypted) this.encrypt(h.payload) else h.payload

    val h_ = h.copy(payload=pay, backs=backs)
    val hash = h_.toHash()

    // signs message if requested (pvt provided or in pvt chain)
    var sig_hash = ""
    //assert(keys[2].isEmpty() || sig_pvt.isEmpty())
    val pvt = if (sig_pvt.isEmpty()) keys[2] else sig_pvt
    if (pvt.isNotEmpty()) {
        val sig = ByteArray(Sign.BYTES)
        val msg = lazySodium.bytes(hash)
        val key = Key.fromHexString(pvt).asBytes
        lazySodium.cryptoSignDetached(sig, msg, msg.size.toLong(), key)
        sig_hash = LazySodium.toHex(sig)
    }
    val sig =
        if (sig_hash.isEmpty())
            null
        else
            Signature(sig_hash, if (sig_pvt.isEmpty()) "" else sig_pvt.pvtToPub())

    val new = Block(h_, now, mutableListOf(), sig, hash)
    this.chainBlock(new)
    return new
}

// CHAIN BLOCK

fun Chain.chainBlock (blk: Block, asr: Boolean = true) {
    if (asr) {
        this.assertBlock(blk)       // skip for testing purposes
    }
    this.saveBlock(blk)
    this.reheads(blk)
    this.save()
}

internal fun Chain.assertBlock (blk: Block) {   // private, but used in tests
    val h = blk.hashable
    assert(blk.hash == h.toHash())

    // checks if has enough reputation to like
    if (h.like != null) {
        val n = h.like.n
        val pub = blk.signature!!.pubkey
        assert(n <= this.pubkeyReputation(blk.time,pub)) {
            "not enough reputation"
        }
    }

    // checks if sig.hash/blk.hash/sig.pubkey match
    if (blk.signature != null) {
        val sig = LazySodium.toBin(blk.signature.hash)
        val msg = lazySodium.bytes(blk.hash)
        val pub = if (blk.signature.pubkey.isEmpty()) this.keys[1] else blk.signature.pubkey
        val key = Key.fromHexString(pub).asBytes
        assert(lazySodium.cryptoSignVerifyDetached(sig, msg, msg.size, key)) { "invalid signature" }
    }
}

private fun Chain.reheads (blk: Block) {
    this.heads.add(blk.hash)
    for (back in blk.hashable.backs) {
        this.heads.remove(back)
        val bk = this.loadBlockFromHash(back,false)
        assert(!bk.fronts.contains(blk.hash))
        bk.fronts.add(blk.hash)
        bk.fronts.sort()
        this.saveBlock(bk)
    }
}

// CRYPTO

private fun Chain.encrypt (payload: String) : String {
    if (this.keys[0].isNotEmpty()) {
        val nonce = lazySodium.nonce(SecretBox.NONCEBYTES)
        val key = Key.fromHexString(this.keys[0])
        return LazySodium.toHex(nonce) + lazySodium.cryptoSecretBoxEasy(payload, nonce, key)
    } else {
        assert(this.keys[1].isNotEmpty())
        val dec = payload.toByteArray()
        val enc = ByteArray(Box.SEALBYTES + dec.size)
        val key = Key.fromHexString(this.keys[1]).asBytes
        val key_ = ByteArray(Box.CURVE25519XSALSA20POLY1305_PUBLICKEYBYTES)
        assert(lazySodium.convertPublicKeyEd25519ToCurve25519(key_, key))
        lazySodium.cryptoBoxSeal(enc, dec, dec.size.toLong(), key_)
        return LazySodium.toHex(enc)
    }
}

private fun Chain.decrypt (payload: String) : Pair<Boolean,String> {
    if (this.keys[0].isEmpty() && this.keys[2].isEmpty()) {
        return Pair(false,payload)
    }

    if (this.keys[0].isNotEmpty()) {
        val idx = SecretBox.NONCEBYTES * 2
        val pay = lazySodium.cryptoSecretBoxOpenEasy(
            payload.substring(idx),
            LazySodium.toBin(payload.substring(0, idx)),
            Key.fromHexString(this.keys[0])
        )
        return Pair(true,pay)
    } else {
        val enc = LazySodium.toBin(payload)
        val dec = ByteArray(enc.size - Box.SEALBYTES)

        val pub = Key.fromHexString(this.keys[1]).asBytes
        val pvt = Key.fromHexString(this.keys[2]).asBytes
        val pub_ = ByteArray(Box.CURVE25519XSALSA20POLY1305_PUBLICKEYBYTES)
        val pvt_ = ByteArray(Box.CURVE25519XSALSA20POLY1305_SECRETKEYBYTES)
        assert(lazySodium.convertPublicKeyEd25519ToCurve25519(pub_,pub))
        assert(lazySodium.convertSecretKeyEd25519ToCurve25519(pvt_,pvt))

        assert(lazySodium.cryptoBoxSealOpen(dec, enc, enc.size.toLong(), pub_, pvt_))
        return Pair(true,dec.toString(Charsets.UTF_8))
    }
}

fun Chain.decideBacks (h: BlockHashable) : Array<String> {
    // if linking a new like that refers to a block in quarantine,
    // ignore current heads and point directly to the liked block only
    if (h.like != null) {
        val ref = h.refs[0]
        val liked = this.loadBlockFromHash(ref, false)
        if (this.evalBlock(liked) != 1) {
            return arrayOf(liked.hash)          // liked block still in quarantine
        }
    }

    // otherwise, link to heads that are not in quarantine
    return this.getHeads(1)
}

// TODO: likes too
fun Chain.getHeads (min: Int) : Array<String> {
    // min=1  : heads that are accepted (not in quarantine)
    // min=0  : heads in quarantine that I want to forward
    // min=-1 : all heads (never used)
    assert(min >= 0)
    fun downs (hash: Hash) : List<Hash> {
        val blk = this.loadBlockFromHash(hash, false)
        return when (this.evalBlock(blk)) {
            in min..1 -> arrayListOf<Hash>(blk.hash)
            else      -> blk.hashable.backs.map(::downs).flatten()
        }
    }
    return this.heads.toList().map(::downs).flatten().toTypedArray()
}

// LIKE

fun Chain.pubkeyReputation (now: Long, pub: String) : Int {
    val b30s = this.traverseFromHeads {
        it.time >= now - 30*day
    }
    //println("B30s: ${b30s.toList()}")
    val mines = b30s
        .filter { it.signature != null &&
                  it.signature.pubkey == pub }          // all I signed
    //println("MINES: $mines")
    val posts = mines
        .filter { it.time <= now - 1*day }              // mines older than 1 day
        .count() * lk
    //println("POSTS: $posts")
    val sent = mines
        .filter { it.hashable.like != null }            // my likes to others
        .map { it.hashable.like!!.n }
        .sum()
    //println("SENT: $sent")
    val recv = b30s
        .filter { it.hashable.like != null &&           // others liked me
                  it.hashable.like.type == LikeType.PUBKEY &&
                  it.hashable.like.ref == pub }
        .map { it.hashable.like!!.n }
        .sum()
    //println("RECV: $recv")
    val all = posts + recv - sent
    return all
}

//      forward rehead
// -1:     no     no        (block dislikes = 1/2 of all likes since post time)
//  0:    yes     no        (any new likes  < 1/4 of all likes in the past 24h, otherwise change to 1)
//  1:    yes    yes        (block likes    = 1/2 of all likes since post time)
fun Chain.evalBlock (blk: Block) : Int {
    // reputation of this block (likes - dislikes)
    val likes = this.traverseFromBacksToFronts (blk, { true })
        .filter { it.hashable.like != null &&                    // which are likes
                  it.hashable.like.type == LikeType.POST &&
                  it.hashable.like.ref == blk.hash }            // for received blk
        .map { it.hashable.like!!.n }                           // get like quantity
        .sum()                                                  // plus-minus

    // all positive likes since block being evaluated
    // (only positive likes because they are the ones that could be used to refuse this block)
    // (negatives not necessarily, e.g., other spam, etc)

    // all positive likes over the past week
    val ps = this.traverseFromHeads { it.time > getNow()-7*day }
        .filter { it.hashable.like !=null }
        .filter { it.hashable.like!!.n > 0 }

    // N of all new positives since after this block
    val nAft = ps.filter { it.time > blk.time }
        .map { it.hashable.like!!.n }
        .sum()

    // N of all new positives over the past week PER DAY
    val nDay = ps
        .map { it.hashable.like!!.n }
        .sum() / 7

    val day6h= min(10, nDay/4)      // 6h of daily likes (minimum 10)
    val day2h= min(5,  nDay/12)     // 2h of daily likes (minimum 5)

    return when {
        // new likes reached 2h of daily likes
        ( likes>nAft/2 && nAft>=day2h) ->  1    // with half positive to blk, ACCEPT
        (-likes>nAft/2 && nAft>=day2h) -> -1    // with half negative to blk, REJECT

        // new likes reached 6h daily likes
        (nAft >= day6h) -> 1                    // time to move on

        else -> 0                               // remain in quarantine
    }
}

// TRAVERSE

fun Chain.traverseFromHeads (f: (Block)->Boolean) : Array<Block> {
    val pending = LinkedList<String>()
    val visited = mutableSetOf<String>()
    val ret = mutableListOf<Block>()

    for (head in this.heads) {
        pending.addLast(head)
    }

    while (pending.isNotEmpty()) {
        val hash = pending.removeFirst()
        val blk = this.loadBlockFromHash(hash,false)
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

fun Chain.traverseFromBacksToFronts (blk: Block, f: (Block)->Boolean) : Array<Block> {
    val pending = LinkedList<String>()
    val visited = mutableSetOf<String>()
    val ret = mutableListOf<Block>()

    for (front in blk.fronts) {
        pending.addLast(front)
    }

    while (pending.isNotEmpty()) {
        val hash = pending.removeFirst()
        val new = this.loadBlockFromHash(hash,false)
        if (!f(new)) {
            break
        }
        for (front in new.fronts) {
            if (! visited.contains(front)) {
                visited.add(front)
                pending.addLast(front)
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
    }
    File(this.root + this.name + "/" + "chain").writeText(this.toJson())
}

fun Chain.saveBlock (blk: Block) {
    File(this.root + this.name + "/blocks/" + blk.hash + ".blk").writeText(blk.toJson()+"\n")
}

fun Chain.loadBlockFromHash (hash: Hash, decrypt: Boolean) : Block {
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

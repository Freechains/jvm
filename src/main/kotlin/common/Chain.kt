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
    val heads : ArrayList<Hash> = arrayListOf(this.toGenHash())
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

fun Chain.toGenHash () : Hash {
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
    val hash = h.toHash()

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

    val sig_pub = if (sig_pvt.isEmpty()) "" else sig_pvt.pvtToPub()
    val new = Block(h, now, mutableListOf(), Pair(sig_hash,sig_pub), hash)
    this.assertBlock(new)  // TODO: remove (paranoid test)
    return new
}

fun Chain.assertBlock (blk: Block) {
    val h = blk.hashable
    assert(blk.hash == h.toHash())
    if (blk.signature.first.isNotEmpty()) {
        val sig = LazySodium.toBin(blk.signature.first)
        val msg = lazySodium.bytes(blk.hash)
        val pub = if (blk.signature.second.isEmpty()) this.keys[1] else blk.signature.second
        val key = Key.fromHexString(pub).asBytes
        assert(lazySodium.cryptoSignVerifyDetached(sig, msg, msg.size, key)) { "invalid signature" }
    }
}

// POST/LIKE

fun Chain.encrypt (encrypt: Boolean, payload: String) : String {
    if (!encrypt) {
        return payload
    }

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

fun Chain.decrypt (decrypt: Boolean, payload: String) : Pair<Boolean,String> {
    if (!decrypt) {
        return Pair(false,payload)
    }

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

fun Chain.post (sig_pvt: String, now: Long, h: BlockHashable) : Block {
    // checks if is owner of read-only chain
    assert(!this.ro || this.keys[0].isNotEmpty() || this.keys[2].isNotEmpty())

    // checks if has enough reputation to like
    if (h.like != null) {
        val n = h.like.first
        assert(n <= this.pubkeyLikes(now,sig_pvt.pvtToPub())) { "not enough reputation" }
    }

    val blk = this.newBlock(sig_pvt, now, h.copy(backs=this.heads.toTypedArray()))
    this.saveBlock(blk)
    this.reheads(blk)
    this.save()
    return blk
}

fun Chain.reheads (blk: Block) {
    this.heads.add(blk.hash)
    for (back in blk.hashable.backs) {
        this.heads.remove(back)
        val old = this.loadBlockFromHash(back,false)
        assert(!old.fronts.contains((blk.hash)))
        old.fronts.add(blk.hash)
        old.fronts.sort()
        this.saveBlock(old)
    }
}

fun Chain.pubkeyLikes (now: Long, pub: String) : Int {
    val b30s = this.traverseFromHeads {
        it.time >= now - 30*day
    }
    //println("B30s: ${b30s.toList()}")
    val mines = b30s
        .filter { it.signature.second == pub }          // all I signed
    //println("MINES: $mines")
    val posts = mines
        .filter { it.time <= now - 1*day }     // mines older than 1 day
        .count()
    //println("POSTS: $posts")
    val sent = mines
        .filter { it.hashable.like != null }            // my likes to others
        .map { it.hashable.like!!.first }
        .sum()
    //println("SENT: $sent")
    val recv = b30s
        .filter { it.hashable.like != null }
        .filter { it.hashable.like!!.second == pub }    // others liked me
        .map { it.hashable.like!!.first }
        .sum()
    //println("RECV: $recv")
    val all = posts + recv - sent
    return all
}

// TRAVERSE

fun Chain.traverseFromHeads (f: (Block)->Boolean) : Array<Block> {
    val pending = LinkedList<String>()
    val visited = mutableSetOf<String>()
    val ret = mutableListOf<Block>()

    for (head in this.heads) {
        pending.add(head)
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
    val (decrypted,pay) = this.decrypt(blk.hashable.encrypted, blk.hashable.payload)
    return blk.copy(hashable = blk.hashable.copy(encrypted = !decrypted, payload = pay))
}

fun Chain.containsBlock (hash: Hash) : Boolean {
    if (this.hash == hash) {
        return true
    } else {
        return File(this.root + this.name + "/blocks/" + hash + ".blk").exists()
    }
}

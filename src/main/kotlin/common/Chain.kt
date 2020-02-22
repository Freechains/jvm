package org.freechains.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import java.io.File
import java.time.Instant

import com.goterl.lazycode.lazysodium.LazySodium
import com.goterl.lazycode.lazysodium.interfaces.Box
import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import com.goterl.lazycode.lazysodium.interfaces.SecretBox
import com.goterl.lazycode.lazysodium.interfaces.Sign
import com.goterl.lazycode.lazysodium.utils.Key
import org.freechains.platform.lazySodium

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

// POST/LIKE

fun Chain.post (payload: Post_or_Like, sig_pvt: String) : Block {
    return this.post(payload, sig_pvt, Instant.now().toEpochMilli())
}

fun Chain.post (payload: Post_or_Like, sig_pvt: String, time: Long) : Block {
    assert(!this.ro || this.keys[0].isNotEmpty() || this.keys[2].isNotEmpty()) // checks if owner of read-only chain
    val payload_ =
        when (payload) {
            is Like -> payload
            is Post -> {
                val post =
                    if (payload.encrypted) {
                        if (this.keys[0].isNotEmpty()) {
                            val nonce = lazySodium.nonce(SecretBox.NONCEBYTES)
                            val key = Key.fromHexString(this.keys[0])
                            LazySodium.toHex(nonce) + lazySodium.cryptoSecretBoxEasy(payload.post,nonce,key)
                        } else {
                            assert(this.keys[1].isNotEmpty())
                            val dec = payload.post.toByteArray()
                            val enc = ByteArray(Box.SEALBYTES + dec.size)
                            val key = Key.fromHexString(this.keys[1]).asBytes
                            val key_ = ByteArray(Box.CURVE25519XSALSA20POLY1305_PUBLICKEYBYTES)
                            assert(lazySodium.convertPublicKeyEd25519ToCurve25519(key_,key))
                            lazySodium.cryptoBoxSeal(enc, dec, dec.size.toLong(), key_)
                            LazySodium.toHex(enc)
                        }
                    } else {
                        payload.post
                    }
                payload.copy(post=post)
            }
        }
    
    val blk = this.newBlock(BlockHashable(time,payload_,this.heads.toTypedArray()), sig_pvt)
    this.saveBlock(blk)
    this.reheads(blk)
    this.save()
    return blk
}

fun Chain.reheads (blk: Block) {
    this.heads.add(blk.hash)
    for (back in blk.hashable.backs) {
        this.heads.remove(back)
        val old = this.loadBlockFromHash(back)
        if (!old.fronts.contains((blk.hash))) {
            val new = old.copy(fronts=(old.fronts+blk.hash).sortedArray())
            this.saveBlock(new)
        }
    }
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

// FILE SYSTEM

fun Chain.save () {
    val dir = File(this.root + this.name + "/blocks/")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    File(this.root + this.name + "/" + "chain").writeText(this.toJson())
}

// NDOE

fun Chain.newBlock (h: BlockHashable, sig_pvt: String = "") : Block {
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

    val sig_pub = if (sig_pvt.isEmpty()) "" else sig_pvt.substring(sig_pvt.length/2)
    val new = Block(h, emptyArray(), Pair(sig_hash,sig_pub), hash)
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

fun Chain.saveBlock (blk: Block) {
    File(this.root + this.name + "/blocks/" + blk.hash + ".blk").writeText(blk.toJson()+"\n")
}

fun Chain.loadBlockFromHash (hash: Hash, decrypt: Boolean = false) : Block {
    val blk = File(this.root + this.name + "/blocks/" + hash + ".blk").readText().jsonToBlock()
    val pay = blk.hashable.payload
    when (pay) {
        is Like ->
            return blk
        is Post ->
            if (decrypt && pay.encrypted && (this.keys[0].isNotEmpty() || this.keys[2].isNotEmpty())) {
                if (this.keys[0].isNotEmpty()) {
                    val idx = SecretBox.NONCEBYTES * 2
                    val post = lazySodium.cryptoSecretBoxOpenEasy(
                        pay.post.substring(idx),
                        LazySodium.toBin(pay.post.substring(0, idx)),
                        Key.fromHexString(this.keys[0])
                    )
                    return blk.copy(hashable=blk.hashable.copy(payload=pay.copy(encrypted=false, post=post)))
                } else {
                    val enc = LazySodium.toBin(pay.post)
                    val dec = ByteArray(enc.size - Box.SEALBYTES)

                    val pub = Key.fromHexString(this.keys[1]).asBytes
                    val pvt = Key.fromHexString(this.keys[2]).asBytes
                    val pub_ = ByteArray(Box.CURVE25519XSALSA20POLY1305_PUBLICKEYBYTES)
                    val pvt_ = ByteArray(Box.CURVE25519XSALSA20POLY1305_SECRETKEYBYTES)
                    assert(lazySodium.convertPublicKeyEd25519ToCurve25519(pub_,pub))
                    assert(lazySodium.convertSecretKeyEd25519ToCurve25519(pvt_,pvt))

                    assert(lazySodium.cryptoBoxSealOpen(dec, enc, enc.size.toLong(), pub_, pvt_))
                    return blk.copy(hashable=blk.hashable.copy(payload=pay.copy(encrypted=false, post=dec.toString(Charsets.UTF_8))))
                }
            } else {
                return blk
            }
    }
}

fun Chain.containsBlock (hash: Hash) : Boolean {
    if (this.hash == hash) {
        return true
    } else {
        return File(this.root + this.name + "/blocks/" + hash + ".blk").exists()
    }
}

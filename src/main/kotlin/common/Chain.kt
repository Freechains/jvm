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
    assert(this[0]=='/' && (this.length==1 || this.last()!='/')) { "invalid chain path: $this"}
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

// PUBLISH

fun Chain.publish (encoding: String, encrypt: Boolean, payload: String) : Block {
    return this.publish(encoding, encrypt, payload, Instant.now().toEpochMilli())
}

fun Chain.publish (encoding: String, encrypt: Boolean, payload: String, time: Long) : Block {
    assert(!this.ro || this.keys[0].isNotEmpty() || this.keys[2].isNotEmpty()) // checks if owner of read-only chain
    val payload2 =
        if (encrypt) {
            if (this.keys[0].isNotEmpty()) {
                val nonce = lazySodium.nonce(SecretBox.NONCEBYTES)
                val key = Key.fromHexString(this.keys[0])
                LazySodium.toHex(nonce) + lazySodium.cryptoSecretBoxEasy(payload,nonce,key)
            } else {
                assert(this.keys[1].isNotEmpty())
                val dec = payload.toByteArray()
                val enc = ByteArray(Box.SEALBYTES + dec.size)
                val key = Key.fromHexString(this.keys[1]).asBytes
                val key_ = ByteArray(Box.CURVE25519XSALSA20POLY1305_PUBLICKEYBYTES)
                assert(lazySodium.convertPublicKeyEd25519ToCurve25519(key_,key))
                lazySodium.cryptoBoxSeal(enc, dec, dec.size.toLong(), key_)
                LazySodium.toHex(enc)
            }
        } else {
            payload
        }

    val blk = this.newBlock(BlockHashable(time,encoding,encrypt,payload2,this.heads.toTypedArray()))
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

fun Chain.newBlock (h: BlockHashable) : Block {
    val hash = h.toHash()

    var signature = ""
    if (keys[2].isNotEmpty()) {
        val sig = ByteArray(Sign.BYTES)
        val msg = lazySodium.bytes(hash)
        val pvt = Key.fromHexString(this.keys[2]).asBytes
        lazySodium.cryptoSignDetached(sig, msg, msg.size.toLong(), pvt)
        signature = LazySodium.toHex(sig)
    }

    val new = Block(h, emptyArray(), signature, hash)
    this.assertBlock(new)  // TODO: remove (paranoid test)
    return new
}

fun Chain.assertBlock (blk: Block) {
    val h = blk.hashable
    assert(blk.hash == h.toHash())
    if (blk.signature.isNotEmpty()) {
        val sig = LazySodium.toBin(blk.signature)
        val msg = lazySodium.bytes(blk.hash)
        val key = Key.fromHexString(this.keys[1]).asBytes
        assert(lazySodium.cryptoSignVerifyDetached(sig, msg, msg.size, key)) { "invalid signature" }
    }
}

fun Chain.saveBlock (blk: Block) {
    File(this.root + this.name + "/blocks/" + blk.hash + ".blk").writeText(blk.toJson()+"\n")
}

fun Chain.loadBlockFromHash (hash: Hash, decrypt: Boolean = false) : Block {
    val blk = File(this.root + this.name + "/blocks/" + hash + ".blk").readText().jsonToBlock()
    val h = blk.hashable
    if (decrypt && h.encrypted && (this.keys[0].isNotEmpty() || this.keys[2].isNotEmpty())) {
        if (this.keys[0].isNotEmpty()) {
            val idx = SecretBox.NONCEBYTES * 2
            val pay = lazySodium.cryptoSecretBoxOpenEasy(
                h.payload.substring(idx),
                LazySodium.toBin(h.payload.substring(0, idx)),
                Key.fromHexString(this.keys[0])
            )
            return blk.copy(hashable = h.copy(encrypted=false, payload=pay))
        } else {
            val enc = LazySodium.toBin(h.payload)
            val dec = ByteArray(enc.size - Box.SEALBYTES)

            val pub = Key.fromHexString(this.keys[1]).asBytes
            val pvt = Key.fromHexString(this.keys[2]).asBytes
            val pub_ = ByteArray(Box.CURVE25519XSALSA20POLY1305_PUBLICKEYBYTES)
            val pvt_ = ByteArray(Box.CURVE25519XSALSA20POLY1305_SECRETKEYBYTES)
            assert(lazySodium.convertPublicKeyEd25519ToCurve25519(pub_,pub))
            assert(lazySodium.convertSecretKeyEd25519ToCurve25519(pvt_,pvt))

            assert(lazySodium.cryptoBoxSealOpen(dec, enc, enc.size.toLong(), pub_, pvt_))
            return blk.copy(hashable = h.copy(encrypted=false, payload=dec.toString(Charsets.UTF_8)))
        }
    } else {
        return blk
    }
}

fun Chain.containsBlock (hash: Hash) : Boolean {
    if (this.hash == hash) {
        return true
    } else {
        return File(this.root + this.name + "/blocks/" + hash + ".blk").exists()
    }
}

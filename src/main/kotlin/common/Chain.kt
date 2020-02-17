package org.freechains.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import java.io.File
import java.time.Instant

import com.goterl.lazycode.lazysodium.LazySodium
import com.goterl.lazycode.lazysodium.interfaces.Sign
import com.goterl.lazycode.lazysodium.utils.Key
import org.freechains.platform.lazySodium

@Serializable
data class Chain (
    val root  : String,
    val name  : String,
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

fun Chain.publish (encoding: String, payload: String) : Block {
    return this.publish(encoding, payload, Instant.now().toEpochMilli())
}

fun Chain.publish (encoding: String, payload: String, time: Long) : Block {
    val blk = this.newBlock(BlockHashable(time,encoding,payload,this.heads.toTypedArray()))
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
            val new = old.copy(fronts=old.fronts+blk.hash)
            this.saveBlock(new)
        }
    }
}

// GENESIS

fun Chain.toGenHash () : Hash {
    return "0_" + this.toHash()
}

// HASH

fun Chain.calcHash (v: String) : String {
    return lazySodium.cryptoGenericHash(v, Key.fromHexString(this.keys[0]))
}

fun Chain.toHash () : String {
    return this.calcHash(this.name+this.keys[0]+this.keys[1])   // exclude private key
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

fun Chain.hashableToHash (h: BlockHashable) : Hash {
    return h.backs.backsToHeight().toString() + "_" + this.calcHash(h.toJson())
}

fun Chain.newBlock (h: BlockHashable) : Block {
    val hash = this.hashableToHash(h)

    var signature = ""
    if (keys[1] != "") {
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
    assert(blk.hash == this.hashableToHash(h))
    if (blk.signature != "") {
        val sig = LazySodium.toBin(blk.signature)
        val msg = lazySodium.bytes(blk.hash)
        val key = Key.fromHexString(this.keys[1]).asBytes
        assert(lazySodium.cryptoSignVerifyDetached(sig, msg, msg.size, key)) { "invalid signature" }
    }
}

fun Chain.saveBlock (blk: Block) {
    File(this.root + this.name + "/blocks/" + blk.hash + ".blk").writeText(blk.toJson()+"\n")
}

fun Chain.loadBlockFromHash (hash: Hash): Block {
    return File(this.root + this.name + "/blocks/" + hash + ".blk").readText().jsonToBlock()
}

fun Chain.containsBlock (hash: Hash) : Boolean {
    if (this.hash == hash) {
        return true
    } else {
        val file = File(this.root + this.name + "/blocks/" + hash + ".blk")
        return file.exists()
    }
}

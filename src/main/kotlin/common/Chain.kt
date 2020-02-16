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

fun Chain.publish (encoding: String, payload: String) : Node {
    return this.publish(encoding, payload, Instant.now().toEpochMilli())
}

fun Chain.publish (encoding: String, payload: String, time: Long) : Node {
    val node = this.newNode(NodeHashable(time,encoding,payload,this.heads.toTypedArray()))
    this.saveNode(node)
    this.reheads(node)
    this.save()
    return node
}

fun Chain.reheads (node: Node) {
    this.heads.add(node.hash)
    for (back in node.hashable.backs) {
        this.heads.remove(back)
        val old = this.loadNodeFromHash(back)
        if (!old.fronts.contains((node.hash))) {
            val new = old.copy(fronts=old.fronts+node.hash)
            this.saveNode(new)
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
    return this.calcHash(this.name)
}

// FILE SYSTEM

fun Chain.save () {
    val dir = File(this.root + this.name + "/nodes/")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    File(this.root + this.name + "/" + "chain").writeText(this.toJson())
}

// NDOE

fun Chain.hashableToHash (h: NodeHashable) : Hash {
    return h.backs.backsToHeight().toString() + "_" + this.calcHash(h.toJson())
}

fun Chain.newNode (h: NodeHashable) : Node {
    val hash = this.hashableToHash(h)

    var signature = ""
    if (keys[1] != "") {
        val sig = ByteArray(Sign.BYTES)
        val msg = lazySodium.bytes(hash)
        val pvt = Key.fromHexString(this.keys[2]).asBytes
        lazySodium.cryptoSignDetached(sig, msg, msg.size.toLong(), pvt)
        signature = LazySodium.toHex(sig)
    }

    val new = Node(h, emptyArray(), signature, hash)
    this.assertNode(new)  // TODO: remove (paranoid test)
    return new
}

fun Chain.assertNode (node: Node) {
    val h = node.hashable
    assert(node.hash == this.hashableToHash(h))
    if (node.signature != "") {
        val sig = LazySodium.toBin(node.signature)
        val msg = lazySodium.bytes(node.hash)
        val key = Key.fromHexString(this.keys[1]).asBytes
        assert(lazySodium.cryptoSignVerifyDetached(sig, msg, msg.size, key)) { "invalid signature" }
    }
}

fun Chain.saveNode (node: Node) {
    File(this.root + this.name + "/nodes/" + node.hash + ".node").writeText(node.toJson()+"\n")
}

fun Chain.loadNodeFromHash (hash: Hash): Node {
    return File(this.root + this.name + "/nodes/" + hash + ".node").readText().jsonToNode()
}

fun Chain.containsNode (hash: Hash) : Boolean {
    if (this.hash == hash) {
        return true
    } else {
        val file = File(this.root + this.name + "/nodes/" + hash + ".node")
        return file.exists()
    }
}

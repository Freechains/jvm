package org.freechains.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import java.io.File
import java.time.Instant

typealias Chain_NW = Pair<String,Byte>

@Serializable
data class Chain (
    val root  : String,
    val name  : String,
    val work  : Byte,
    val keys  : Array<String>   // [shared,public,private]
) {
    val hash  : String = this.toHash()
    val heads : ArrayList<Hash> = arrayListOf(this.toGenHash())
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
    val node = Node(NodeHashable(time,0,payload,emptyArray()), encoding, this.heads.toTypedArray(), "", null)
    node.setNonceHashSigWithWorkKeys(this.work,this.keys)
    this.saveNode(node)
    this.reheads(node)
    this.save()
    return node
}

fun Chain.reheads (node: Node) {
    this.heads.add(node.hash!!)
    for (back in node.hashable.backs) {
        this.heads.remove(back)
        val old = this.loadNodeFromHash(back)
        if (!old.fronts.contains((node.hash!!))) {
            val new = Node(
                NodeHashable(old.hashable.time, old.hashable.nonce, old.hashable.payload, old.hashable.backs),
                old.encoding,old.fronts + node.hash!!, old.signature, old.hash!!
            )
            this.saveNode(new)
        }
    }
}

// GENESIS

fun Chain.toGenHash () : Hash {
    return "0_" + this.toHash()
}

// CONVERSIONS

fun Chain.toPath () : String {
    return this.name + "/" + this.work
}

fun String.pathToChainNW () : Chain_NW {
    val all = this.trimEnd('/').split("/").toMutableList()
    val work = all.removeAt(all.size-1)
    val name = all.joinToString("/")
    return Chain_NW(name,work.toByte())
}

fun String.pathCheck () : String {
    assert(this[0] == '/' && this.last() != '/') { "invalid chain path: $this"}
    return this
}

// HASH

fun Chain.toHash () : String {
    return this.toByteArray().toHash(this.keys[0])
}

private fun Chain.toByteArray () : ByteArray {
    val bytes = ByteArray(this.name.length + 1)
    var off = 0
    for (v in this.name) {
        bytes.set(off, v.toByte())
        off += 1
    }
    bytes.set(off, this.work)
    off += 1
    return bytes
}

// FILE SYSTEM

fun Chain.save () {
    val dir = File(this.root + this.toPath())
    if (!dir.exists()) {
        dir.mkdirs()
    }
    File(this.root + this.toPath() + ".chain").writeText(this.toJson())
}

// NDOE

fun Chain.saveNode (node: Node) {
    File(this.root + this.toPath() + "/" + node.hash!! + ".node").writeText(node.toJson()+"\n")
}

fun Chain.loadNodeFromHash (hash: Hash): Node {
    return File(this.root + this.toPath() + "/" + hash + ".node").readText().jsonToNode()
}

fun Chain.containsNode (hash: Hash) : Boolean {
    if (this.hash == hash) {
        return true
    } else {
        val file = File(this.root + this.toPath() + "/" + hash + ".node")
        return file.exists()
    }
}

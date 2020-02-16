package org.freechains.common

import com.goterl.lazycode.lazysodium.LazySodium
import com.goterl.lazycode.lazysodium.utils.Key
import kotlinx.serialization.Serializable
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlin.math.max

typealias Hash = String

@Serializable
data class NodeHashable (
    val time      : Long,           // TODO: ULong
    val encoding  : String,         // payload encoding
    val payload   : String,
    val backs     : Array<Hash>     // back links (previous nodes)
)

@Serializable
data class Node (
    val hashable  : NodeHashable,   // things to hash
    val fronts    : Array<Hash>,    // front links (next nodes)
    val signature : String,         // hash signature
    val hash      : Hash            // hash of hashable
) {
    val height    : Int = this.hashable.backs.backsToHeight()
}

fun Array<Hash>.backsToHeight () : Int {
    return when {
        this.isEmpty() -> 0
        else -> this.fold(0, { cur, hash -> max(cur, hash.toHeight()) }) + 1
    }
}

// JSON

fun NodeHashable.toJson (): String {
    @UseExperimental(UnstableDefault::class)
    val json = Json(JsonConfiguration(prettyPrint=true))
    return json.stringify(NodeHashable.serializer(), this)
}

fun Node.toJson (): String {
    @UseExperimental(UnstableDefault::class)
    val json = Json(JsonConfiguration(prettyPrint=true))
    return json.stringify(Node.serializer(), this)
}

fun String.jsonToNode (): Node {
    @UseExperimental(UnstableDefault::class)
    val json = Json(JsonConfiguration(prettyPrint=true))
    return json.parse(Node.serializer(), this)
}

// HH

private fun Hash.toHeight () : Int {
    val (height,_) = this.split("_")
    return height.toInt()
}

// HASH

fun Node.recheck (keys: Array<String>) {
    if (this.signature != "") {
        val sig = LazySodium.toBin(this.signature)
        val msg = lazySodium.bytes(this.hash)
        val key = Key.fromHexString(keys[1]).asBytes
        assert(lazySodium.cryptoSignVerifyDetached(sig, msg, msg.size, key)) { "invalid signature" }
    }
}
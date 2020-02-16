package org.freechains.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlin.math.max

import com.goterl.lazycode.lazysodium.LazySodium
import com.goterl.lazycode.lazysodium.utils.Key
import org.freechains.platform.lazySodium

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

private fun Hash.toHeight () : Int {
    val (height,_) = this.split("_")
    return height.toInt()
}

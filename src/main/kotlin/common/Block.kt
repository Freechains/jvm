package org.freechains.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlin.math.max

typealias Hash = String

enum class LikeType {
    POST, PUBKEY
}

@Serializable
data class Like (
    val n    : Int,       // +X: like, -X: dislike
    val type : LikeType,
    val ref  : String     // target public key or post hash
)

@Serializable
data class Signature (
    val hash   : String,    // signature
    val pubkey : String     // of pubkey (if "", assumes pub of chain)
)

@Serializable
data class BlockHashable (
    val like      : Like?,
    val encoding  : String,         // payload encoding
    val encrypted : Boolean,        // payload is encrypted (method depends on chain)
    val payload   : String,
    val refs      : Array<String>,  // post hash or user pubkey
    val backs     : Array<Hash>     // back links (previous blocks)
)

@Serializable
data class Block (
    val hashable  : BlockHashable,       // things to hash
    val time      : Long,                // TODO: ULong
    val fronts    : MutableList<Hash>,   // front links (next blocks)
    val signature : Signature?,
    val hash      : Hash                 // hash of hashable
) {
    val height    : Int = this.hashable.backs.backsToHeight()
}

fun Array<Hash>.backsToHeight () : Int {
    return when {
        this.isEmpty() -> 0
        else -> this.fold(0, { cur, hash -> max(cur, hash.toHeight()) }) + 1
    }
}

fun BlockHashable.toJson (): String {
    @UseExperimental(UnstableDefault::class)
    val json = Json(JsonConfiguration(prettyPrint=true))
    return json.stringify(BlockHashable.serializer(), this)
}

fun Block.toJson (): String {
    @UseExperimental(UnstableDefault::class)
    val json = Json(JsonConfiguration(prettyPrint=true))
    return json.stringify(Block.serializer(), this)
}

fun String.jsonToBlock (): Block {
    @UseExperimental(UnstableDefault::class)
    val json = Json(JsonConfiguration(prettyPrint=true))
    return json.parse(Block.serializer(), this)
}

private fun Hash.toHeight () : Int {
    val (height,_) = this.split("_")
    return height.toInt()
}

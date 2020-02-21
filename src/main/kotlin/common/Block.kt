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
data class BlockHashable (
    val time      : Long,           // TODO: ULong
    val encoding  : String,         // payload encoding
    val encrypted : Boolean,        // payload is encrypted (method depends on chain)
    val payload   : String,
    val backs     : Array<Hash>     // back links (previous blocks)
)

@Serializable
data class Block (
    val hashable  : BlockHashable,       // things to hash
    val fronts    : Array<Hash>,         // front links (next blocks)
    val signature : Pair<String,String>, // <hash,pub> (if pub=="", assumes pub of chain)
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

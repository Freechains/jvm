package org.freechains.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlin.math.sqrt

typealias Hash = String

enum class State {
    MISSING, BLOCKED, LINKED, REJECTED, ACCEPTED, ALL
}

fun State.toString_ () : String {
    return when (this) {
        State.ALL      -> "all"
        State.ACCEPTED -> "accepted"
        State.REJECTED -> "rejected"
        State.LINKED   -> "linked"
        State.BLOCKED  -> "blocked"
        State.MISSING  -> "missing"
    }
}

fun String.toState () : State {
    return when (this) {
        "all"      -> State.ALL
        "accepted" -> State.ACCEPTED
        "rejected" -> State.REJECTED
        "linked"   -> State.LINKED
        "blocked"  -> State.BLOCKED
        "missing"  -> State.MISSING
        else       -> error("bug found")
    }
}

@Serializable
data class Like (
    val n    : Int,     // +1: like, -1: dislike
    val hash : Hash     // target post hash
)

@Serializable
data class Signature (
    val hash1 : String,    // signature
    //val hash2 : String,    // signature
    val pub   : HKey       // of pubkey (if "", assumes pub of chain)
)

@Serializable
data class Immut (
    val time    : Long,         // author's timestamp
    val code    : String,       // payload encoding
    val crypt   : Boolean,      // payload is encrypted (method depends on chain)
    val payload : String,
    val prev    : Hash?,        // previous author's post (null if anonymous // gen if first post)
    val like    : Like?,        // point to liked post
    val backs   : Array<Hash>   // back links (happened-before blocks)
)

@Serializable
data class Block (
    val immut    : Immut,           // things to hash
    val hash1    : Hash,            // hash of immut, except payload
    //val hash2    : Hash,            // hash of payload
    val sign     : Signature?
) {
    var fronts   : MutableList<Hash> = mutableListOf() // front links (next blocks)
}

fun Immut.toJson (): String {
    @UseExperimental(UnstableDefault::class)
    val json = Json(JsonConfiguration(prettyPrint=true))
    return json.stringify(Immut.serializer(), this)
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

fun Hash.toHeight () : Int {
    val (height,_) = this.split("_")
    return height.toInt()
}

fun Hash.hashIsBlock () : Boolean {
    return this.contains('_')   // otherwise is pubkey
}

fun Block.isFrom (pub: HKey) : Boolean {
    return (this.sign!=null && this.sign.pub==pub)
}

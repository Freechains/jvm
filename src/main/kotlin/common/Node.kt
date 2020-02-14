package org.freechains.common

//import java.security.MessageDigest

import com.goterl.lazycode.lazysodium.LazySodiumJava
import com.goterl.lazycode.lazysodium.SodiumJava
import com.goterl.lazycode.lazysodium.utils.Key
import kotlinx.serialization.Serializable
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlin.math.max


private var lazySodium: LazySodiumJava = LazySodiumJava(SodiumJava())

typealias Hash = String

@Serializable
data class Node (
    val time     : Long,             // TODO: ULong
    var nonce    : Long,             // TODO: ULong
    val encoding : String,
    val payload  : String,
    val backs    : Array<Hash>,
    val fronts   : Array<Hash> = arrayOf()
) {
    val height   : Int = if (this.backs.isEmpty()) 0 else this.backs.fold(0, { cur,hash -> max(cur,hash.toHeight()) }) + 1
    var hash     : Hash? = null
}

// JSON

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

fun Hash.toHash () : String {
    val (_,hash) = this.split("_")
    return hash
}

// HASH

fun ByteArray.toHash (shared: String) : String {
    return lazySodium.cryptoGenericHash(this.toString(Charsets.UTF_8), Key.fromPlainString(shared))
    //return MessageDigest.getInstance("SHA-256").digest(this).toHexString()
}

fun Node.setNonceHashWithWorkShared (work: Byte, shared: String) {
    while (true) {
        val hash = this.toByteArray().toHash(shared)
        //println(hash)
        if (hash2work(hash) >= work) {
            this.hash = this.height.toString() + "_" + hash
            return
        }
        this.nonce++
    }
}

fun Node.recheck (shared: String) {
    assert(this.hash!! == this.height.toString() + "_" + this.toByteArray().toHash(shared))
}

private fun hash2work (hash: String): Int {
    var work = 0
    for (i in hash.indices step 2) {
        val bits = hash.substring(i, i+2).toInt(16)
        for (j in 7 downTo 0) {
            //println("$work, $bits, $j, ${bits shr j}")
            if (((bits shr j) and 1) == 1) {
                return work
            }
            work += 1
        }
    }
    error("bug found!")
}

private fun Node.toByteArray (): ByteArray {
    val bytes = ByteArray(8 + 8 + 4 + this.payload.length + this.backs.size*64 + 64)
    var off = 0
    bytes.setLongAt(off, this.time)
    off += 8
    bytes.setLongAt(off, this.nonce)
    off += 8
    for (v in this.payload) {
        bytes.set(off, v.toByte())
        off += 1
    }
    for (hash in this.backs) {
        for (v in hash.toHash()) {
            bytes.set(off, v.toByte())
            off += 1
        }
    }
    return bytes
}

private fun ByteArray.setLongAt (index: Int, value: Long) {
    this.set(index + 0, (value shr  0).toByte())
    this.set(index + 1, (value shr  8).toByte())
    this.set(index + 2, (value shr 16).toByte())
    this.set(index + 3, (value shr 24).toByte())
    this.set(index + 4, (value shr 32).toByte())
    this.set(index + 5, (value shr 40).toByte())
    this.set(index + 6, (value shr 48).toByte())
    this.set(index + 7, (value shr 56).toByte())
}

fun ByteArray.toHexString () : String {
    return this.fold("", { str, it -> str + "%02x".format(it) })
}

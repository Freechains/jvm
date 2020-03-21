package org.freechains.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import java.io.File

import com.goterl.lazycode.lazysodium.LazySodium
import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import com.goterl.lazycode.lazysodium.interfaces.Sign
import com.goterl.lazycode.lazysodium.utils.Key
import org.freechains.platform.lazySodium
import java.lang.Integer.max
import java.lang.Integer.min
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.absoluteValue

// modifies heads
fun Chain.blockReject (hash: Hash) {
    val newHeads = mutableSetOf<Hash>()
    var todo = false

    for (head in this.heads) {
        when {
            (head == hash) -> newHeads.add(head)
            this.isBack(listOf(head), hash) -> {
                val blk = this.fsLoadBlock(head,null)
                this.fsSaveBlock(blk,"/rems/")
                this.fsRemBlock(blk.hash)
                newHeads.addAll(blk.immut.backs)
                todo = true
            }
            else -> newHeads.add(head)
        }
    }

    this.heads.clear()
    this.heads.addAll(newHeads)
    this.fsSave()

    if (todo) {
        return this.blockReject(hash)
    }
}

fun Chain.blockBan (hash: Hash) {
    this.blockReject(hash)

    val newHeads = mutableSetOf<Hash>()
    newHeads.addAll(this.heads)

    val blk = this.fsLoadBlock(hash,null)
    this.fsSaveBlock(blk,"/bans/")
    this.fsRemBlock(blk.hash)
    newHeads.remove(blk.hash)
    newHeads.addAll(blk.immut.backs)

    this.heads.clear()
    this.heads.addAll(newHeads)
    this.fsSave()
}

fun Chain.blockUnban (hash: Hash) {
    val blk = this.fsLoadBlock(hash, null, "/bans/")
    this.fsSaveBlock(blk)
    this.fsRemBlock(blk.hash, "/bans/")
    this.heads.add(blk.hash)
    this.fsSave()
}

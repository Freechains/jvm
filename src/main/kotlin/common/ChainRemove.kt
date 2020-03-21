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

/*
fun Chain.blockRemove (hash: Hash, isBan: Boolean) {
    val blk = this.fsLoadBlock(hash, null)
    //println("rem $hash // ${blk.fronts}")

    // - remove myself from heads
    // - add my backs as heads
    if (this.heads.contains(hash)) {
        this.heads.remove(hash)
        for (it in blk.immut.backs) {
            assert(!this.heads.contains(it)) { "TODO" }
            this.heads.add(it)
        }
    }

    val dir = if (isBan) "/bans/" else "/blocks/"
    if (isBan) {
        this.fsSaveBlock(blk,dir)
        this.fsRemBlock(blk.hash)
    }

    this.fsSave()
}

fun Chain.blockUnRemove (hash: Hash, isBan: Boolean, f: (Block) -> Boolean = {true}) {
    val dir = if (isBan) "/bans/" else "/blocks/"
    val blk = this.fsLoadBlock(hash, null, dir)

    if (!f(blk)) {
        return
    }

    if (isBan) {
        //println("unban: ${blk.hash}")
        this.fsSaveBlock(blk)
        this.fsRemBlock(blk.hash, dir)
    }

    this.heads.add(blk.hash)
    this.fsSaveBlock(blk)
    this.fsSave()

    for (fr in blk.fronts) {
        this.blockUnRemove(fr, isBan, f)
    }
}
*/
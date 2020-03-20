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

fun Chain.blockReject (hash: Hash) {
    val set = this.heads.toMutableSet()
    var todo = false

    for (head in set) {
        val headLeadsToHash = this
            .bfsFromHeads(listOf(head),true) { it.hash != head }
            .let { it.last().hash == head }
        if (headLeadsToHash) {
            todo = true
            set.remove(head)
            set.addAll(this.fsLoadBlock(head,null).immut.backs)
        }
    }

    this.heads.clear()
    this.heads.addAll(set)
    this.fsSave()

    if (todo) {
        return this.blockReject(hash)
    }
}

fun Chain.blockUnReject (hash: Hash) {
    // change to PENDING
    val blk = this.fsLoadBlock(hash, null)
    blk.localTime = getNow()
    this.fsSaveBlock(blk)

    // TODO: search fronts in fs (X+1)_xxx
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

    val dir = if (isBan) "/banned/" else "/blocks/"
    if (isBan) {
        this.fsSaveBlock(blk,dir)
        this.fsRemBlock(blk.hash)
    }

    this.fsSave()
}

fun Chain.blockUnRemove (hash: Hash, isBan: Boolean, f: (Block) -> Boolean = {true}) {
    val dir = if (isBan) "/banned/" else "/blocks/"
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
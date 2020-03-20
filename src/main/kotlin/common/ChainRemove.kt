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
    this.blockRemove(hash, false)

    // unremove myself and all likes to it
    this.blockUnRemove(hash, false, {
        (it.hash == hash) || (it.immut.like!=null && it.immut.like.hash==hash)
    })
}

fun Chain.blockUnReject (hash: Hash) {
    // change to PENDING
    val blk = this.fsLoadBlock(hash, null)
    blk.localTime = getNow()
    this.fsSaveBlock(blk)

    this.blockUnRemove(hash, false)
}

fun Chain.blockRemove (hash: Hash, isBan: Boolean) {
    val blk = this.fsLoadBlock(hash, null)
    //println("rem $hash // ${blk.fronts}")

    // remove all my fronts as well
    for (it in blk.fronts) {
        this.blockRemove(it, isBan)
    }

    // - remove myself from heads
    // - add my backs as heads, unless they lead to a head
    if (this.heads.contains(hash)) {
        fun leadsToHeads (hash: Hash) : Boolean {
            return (
                this.heads.contains(hash) ||
                    this.fsLoadBlock(hash,null).let {
                        it.fronts.any { leadsToHeads(it) }
                    }
                )
        }
        this.heads.remove(hash)
        for (it in blk.immut.backs) {
            if (!leadsToHeads(it)) {
                this.heads.add(it)
            }
        }
    }

    // refronts: remove myself as front of all my backs
    for (bk in blk.immut.backs) {
        this.fsLoadBlock(bk, null).let {
            it.fronts.remove(hash)
            this.fsSaveBlock(it)
        }
    }

    val dir = if (isBan) "/banned/" else "/blocks/"
    if (isBan) {
        this.fsSaveBlock(blk,dir)
        this.fsRemBlock(blk.hash)
    }

    // fronts removed in nested refronts will be restored here
    this.fsSaveBlock(blk, dir)
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
    this.addBlockAsFrontOfBacks(blk, true)
    this.fsSaveBlock(blk)
    this.fsSave()

    for (fr in blk.fronts) {
        this.blockUnRemove(fr, isBan, f)
    }
}

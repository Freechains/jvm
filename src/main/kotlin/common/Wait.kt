package org.freechains.common

import java.util.*
import kotlin.collections.HashMap

typealias WaitLists = HashMap<Chain,WaitList>

fun WaitLists.createGet (chain: Chain) : WaitList {
    if (!this.containsKey(chain)) {
        this[chain] = WaitList(compareBy<Block>{
            it.signature.let {
                if (it==null) 0 else chain.getRep(it.pubkey, getNow())
            }
        }.thenBy {
            it.hash
        })
    }
    return this[chain]!!
}

class WaitList (cmp: Comparator<in Block>) {
    var nextTime : Long? = null
    var nextBlock : Block? = null
    val list : SortedSet<Block> = sortedSetOf(cmp)
    fun add (blk: Block, now: Long) {
        if (this.nextBlock == null) {
            this.nextBlock = blk
            this.nextTime = now + T2H_waitLists
        } else if (this.list.size <= N20_waitLists) {
            this.list.add(blk)
        }
    }
    fun rem (now: Long) : Block? {
        //println("$now >= ${this.nextTime}")
        if (this.nextTime==null || this.nextTime!!>now) {
            return null
        }
        val ret = this.nextBlock!!
        if (this.list.isEmpty()) {
            this.nextBlock = null
            this.nextTime = null
        } else {
            this.nextTime = this.nextTime!! + T2H_waitLists

            val first = this.list.first()
            this.list.remove(first)
            this.nextBlock = first
        }
        return ret
    }
}

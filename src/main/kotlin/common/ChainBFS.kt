package org.freechains.common

import java.util.*

fun Chain.bfsFrontsIsFromTo (from: Hash, to: Hash) : Boolean {
    //println(this.bfsFirst(listOf(from), true) { it.hash == to })
    return to == (this.bfsFrontsFirst(from) { it.hash == to }!!.hash)
}

fun Chain.bfsBacksFindAuthor (pub: String) : Block? {
    return this.bfsBacksFirst(this.getHeads(State.ALL)) { it.isFrom(pub) }
}

fun Chain.bfsFrontsFirst (start: Hash, pred: (Block) -> Boolean) : Block? {
    return this.bfsFirst(listOf(start), true, pred)
}

fun Chain.bfsBacksFirst (heads: List<Hash>, pred: (Block) -> Boolean) : Block? {
    return this.bfsFirst(heads, false, pred)
}

fun Chain.bfsBacksAuthor (heads: List<Hash>, pub: String) : List<Block> {
    return this
        .bfsBacksFirst(heads) { it.isFrom(pub) }.let { blk ->
            if (blk == null) {
                emptyList()
            } else {
                fun f (blk: Block) : List<Block> {
                    return listOf(blk) + blk.immut.prev.let {
                        if (it == null) emptyList() else f(this.fsLoadBlock(it,null))
                    }
                }
                f(blk)
            }
        }
}

private fun Chain.bfsFirst (starts: List<Hash>, fromGen: Boolean, pred: (Block) -> Boolean) : Block? {
    return this
        .bfs(starts,true, fromGen) { !pred(it) }
        .last()
        .let {
            if (it.hash == this.getGenesis())
                null
            else
                it
        }
}

fun Chain.bfsFrontsAll (start: Hash = this.getGenesis()) : List<Block> {
    return this.bfsFronts(start,false) { true }
}

fun Chain.bfsBacksAll (heads: List<Hash>) : List<Block> {
    return this.bfsBacks(heads,false) { true }
}

fun Chain.bfsFronts (start: Hash, inc: Boolean, ok: (Block) -> Boolean) : List<Block> {
    return this.bfs(listOf(start), inc, true, ok)
}

fun Chain.bfsBacks (starts: List<Hash>, inc: Boolean, ok: (Block) -> Boolean) : List<Block> {
    return this.bfs(starts, inc, false, ok)
}

internal fun Chain.bfs (starts: List<Hash>, inc: Boolean, fromGen: Boolean, ok: (Block) -> Boolean) : List<Block> {
    val ret = mutableListOf<Block>()

    val pending =
        if (fromGen) {
            TreeSet<Block>(compareBy { it.immut.time })
        } else {
            TreeSet<Block>(compareByDescending { it.immut.time })       // TODO: val cmp = ...
        }
    pending.addAll(starts.map { this.fsLoadBlock(it,null) })

    val visited = starts.toMutableSet()

    while (pending.isNotEmpty()) {
        val blk = pending.first()
        pending.remove(blk)
        if (!ok(blk)) {
            if (inc) {
                ret.add(blk)
            }
            break
        }

        val list = if (fromGen) blk.fronts else blk.immut.backs.toList()
        pending.addAll(list.minus(visited).map { this.fsLoadBlock(it,null) })
        visited.addAll(list)
        ret.add(blk)
    }

    return ret
}

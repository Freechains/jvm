package org.freechains.common

import java.time.Instant

const val min  = (1000 * 60).toLong()
const val hour = 60*min
const val day  = 24*hour

const val lk = 1000     // rewards for post after 24h

const val T2H_waitLists = 2*hour    // period between accepting consecutive old blocks
const val N12_waitLists = 24        // size of buffer (24 --> 48h --> 2d)

fun String.pvtToPub () : String {
    return this.substring(this.length/2)
}

var NOW : Long? = null

fun getNow () : Long {
    return Instant.now().toEpochMilli() - (if (NOW == null) 0 else NOW!!)
}

fun String.nowToTime () : Long {
    return if (this == "now") getNow() else this.toLong()
}


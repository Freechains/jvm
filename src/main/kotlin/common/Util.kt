package org.freechains.common

import java.time.Instant

const val min  = (1000 * 60).toLong()
const val hour = 60*min
const val day  = 24*hour

const val lk = 1000     // rewards for post after 24h

fun String.pvtToPub () : String {
    return this.substring(this.length/2)
}

var NOW : Long? = null

fun getNow () : Long {
    return if (NOW != null) NOW!! else Instant.now().toEpochMilli()
}

fun String.nowToTime () : Long {
    return if (this == "now") getNow() else this.toLong()
}


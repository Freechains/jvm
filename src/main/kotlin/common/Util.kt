package org.freechains.common

import java.time.Instant

const val min  = (1000 * 60).toLong()
const val hour = 60*min
const val day  = 24*hour

const val lk = 1000
const val lkRatio = 1/2

fun String.pvtToPub () : String {
    return this.substring(this.length/2)
}

fun getNow () : Long {
    return Instant.now().toEpochMilli()
}

fun String.nowToTime () : Long {
    return if (this == "now") getNow() else this.toLong()
}


package org.freechains.common

import java.time.Instant

const val seg  = 1000.toLong()
const val min  = 60*seg
const val hour = 60*min
const val day  = 24*hour

const val T90_rep     = 90*day          // consider last 90d for reputation
const val T30M_future = 30*min          // refuse posts +30m in the future
const val T120_past   = 4*T90_rep/3     // reject posts +120d in the past
const val T2H_past    = 2*hour          // refuse posts +2h in the past, add to quarantine
const val T1D_rep     = 1*day           // account to reputation posts older than 1 day only (count negatively otherwise)

const val mlk = 1
const val lk  = 1000*mlk                // rewards for post after 24h
const val LK30_max = 30*lk

fun String.pvtToPub () : String {
    return this.substring(this.length/2)
}

var NOW : Long? = null

fun setNow (t: Long) {
    NOW = Instant.now().toEpochMilli() - t
}

fun getNow () : Long {
    return Instant.now().toEpochMilli() - (if (NOW == null) 0 else NOW!!)
}

fun String.nowToTime () : Long {
    return if (this == "now") getNow() else this.toLong()
}

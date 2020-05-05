package org.freechains.common

import java.io.DataInputStream
import java.io.DataOutputStream

/*
 * (\n) = (0xA)
 * (\r) = (0xD)
 * Windows = (\r\n)
 * Unix = (\n)
 * Mac = (\r)
 */

fun DataInputStream.readLineX () : String {
    val ret = mutableListOf<Byte>()
    while (true) {
        val c = this.readByte()
        if (c == '\r'.toByte()) {
            assert(this.readByte() == '\n'.toByte())
            break
        }
        if (c == '\n'.toByte()) {
            break
        }
        ret.add(c)
    }
    return ret.toByteArray().toString(Charsets.UTF_8)
}

fun DataOutputStream.writeLineX (v: String) {
    this.writeBytes(v)
    this.writeByte('\n'.toInt())
}

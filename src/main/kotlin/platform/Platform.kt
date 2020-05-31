package org.freechains.platform

import java.io.DataInputStream
import com.goterl.lazycode.lazysodium.LazySodiumJava
import com.goterl.lazycode.lazysodium.SodiumJava

val fsRoot = "/"

fun DataInputStream.readNBytesX (len: Int): ByteArray {
    return this.readNBytes(len)
}

fun DataInputStream.readAllBytesX (): ByteArray {
    return this.readAllBytes()
}

val lazySodium: LazySodiumJava = LazySodiumJava(SodiumJava())
package org.freechains.common

import com.goterl.lazycode.lazysodium.LazySodium
import com.goterl.lazycode.lazysodium.interfaces.Box
import com.goterl.lazycode.lazysodium.interfaces.SecretBox
import com.goterl.lazycode.lazysodium.utils.Key
import org.freechains.platform.lazySodium

private const val len = "6F99999751DE615705B9B1A987D8422D75D16F5D55AF43520765FA8C5329F7053CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322".length

typealias HKey = String

fun HKey.keyIsPrivate () : Boolean {
    return this.length == len
}

// ENCRYPT

fun String.encrypt (key: HKey) : String {
    return if (key.keyIsPrivate()) this.encryptPublic(key) else this.encryptShared(key)
}

fun String.encryptShared (key: HKey) : String {
    val nonce = lazySodium.nonce(SecretBox.NONCEBYTES)
    val key_ = Key.fromHexString(key)
    return LazySodium.toHex(nonce) + lazySodium.cryptoSecretBoxEasy(this, nonce, key_)
}

fun String.encryptPublic (key: HKey) : String {
    val dec = this.toByteArray()
    val enc = ByteArray(Box.SEALBYTES + dec.size)
    val key0 = Key.fromHexString(key.pvtToPub()).asBytes
    val key1 = ByteArray(Box.CURVE25519XSALSA20POLY1305_PUBLICKEYBYTES)
    assert(lazySodium.convertPublicKeyEd25519ToCurve25519(key1, key0))
    lazySodium.cryptoBoxSeal(enc, dec, dec.size.toLong(), key1)
    return LazySodium.toHex(enc)
}

// DECRYPT

fun String.decrypt (key: HKey) : String {
    return if (key.keyIsPrivate()) this.decryptPrivate(key) else this.decryptShared(key)
}

fun String.decryptShared (key: HKey) : String {
    val idx = SecretBox.NONCEBYTES * 2
    return lazySodium.cryptoSecretBoxOpenEasy(
        this.substring(idx),
        LazySodium.toBin(this.substring(0, idx)),
        Key.fromHexString(key)
    )
}

fun String.decryptPrivate (key: HKey) : String {
    val enc = LazySodium.toBin(this)
    val dec = ByteArray(enc.size - Box.SEALBYTES)

    val pub = Key.fromHexString(key.pvtToPub()).asBytes
    val pvt = Key.fromHexString(key).asBytes
    val pub_ = ByteArray(Box.CURVE25519XSALSA20POLY1305_PUBLICKEYBYTES)
    val pvt_ = ByteArray(Box.CURVE25519XSALSA20POLY1305_SECRETKEYBYTES)
    assert(lazySodium.convertPublicKeyEd25519ToCurve25519(pub_,pub))
    assert(lazySodium.convertSecretKeyEd25519ToCurve25519(pvt_,pvt))

    assert(lazySodium.cryptoBoxSealOpen(dec, enc, enc.size.toLong(), pub_, pvt_))
    return dec.toString(Charsets.UTF_8)
}
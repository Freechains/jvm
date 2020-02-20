import com.goterl.lazycode.lazysodium.LazySodium
import com.goterl.lazycode.lazysodium.LazySodiumJava
import com.goterl.lazycode.lazysodium.SodiumJava
import com.goterl.lazycode.lazysodium.interfaces.Box
import com.goterl.lazycode.lazysodium.interfaces.SecretBox
import com.goterl.lazycode.lazysodium.utils.Key
import com.goterl.lazycode.lazysodium.utils.KeyPair
import kotlinx.serialization.Serializable
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.freechains.common.*
import org.junit.jupiter.api.MethodOrderer.Alphanumeric
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.util.*
import kotlin.concurrent.thread


@Serializable
data class MeuDado(val v: String)

/*
 *  TODO:
 *  - 948 -> 852 -> 841 -> 931 -> 1041 -> 1101 -> 980 -> (no tests) -> 736 -> 809 LOC
 *  - 10556 -> 10557 -> 10553 -> 10553 -> 10555 KB
 *  - chain locks (test sends in parallel)
 *  - put --sign
 *  - all use cases (chain cfg e usos da industria)
 *  - commands with auth. ip port time to avoid reuse
 *  - sistema de reputacao
 *  - testes antigos
 *  - RX Kotlin
 *  - pipes / filtros
 *  - freechains host restart
 *  - Future:
 *  - Xfreechains
 *    - chain xtraverse
 *  - Android WiFi Direct
 *  - crypto host-to-host
 *  - RPi: cable + router + phones
 */

@TestMethodOrder(Alphanumeric::class)
class Tests {

    @Test
    fun a_reset () {
        assert( File("/tmp/freechains/tests/").deleteRecursively() )
    }

    @Test
    fun a2_json () {
        val bs : MutableList<Byte> = mutableListOf()
        for (i in 0..255) {
            bs.add(i.toByte())
        }
        val x = bs.toByteArray().toString(Charsets.ISO_8859_1)
        //println(x)
        val s = MeuDado(x)
        //println(s)
        @UseExperimental(UnstableDefault::class)
        val json = Json(JsonConfiguration(prettyPrint=true))
        val a = json.stringify(MeuDado.serializer(), s)
        val b = json.parse(MeuDado.serializer(), a)
        val c = b.v.toByteArray(Charsets.ISO_8859_1)
        assert(bs.toByteArray().contentToString() == c.contentToString())
    }

    @Test
    fun b1_chain () {
        val host1 = Host_create("/tmp/freechains/tests/local/")
        val chain1 = Chain("/tmp/freechains/tests/local/chains/", "/uerj", false, arrayOf("secret","",""))
        //println("Chain /uerj: ${chain1.toHash()}")
        chain1.save()
        val chain2 = host1.loadChain(chain1.name)
        assertThat(chain1.hashCode()).isEqualTo(chain2.hashCode())
    }

    @Test
    fun b2_block () {
        val chain = Chain("/tmp/freechains/tests/local/chains/", "/uerj", false, arrayOf("","",""))
        val blk = chain.newBlock(BlockHashable(0,"utf8", false,"111", arrayOf(chain.toGenHash())))
        chain.saveBlock(blk)
        val blk2 = chain.loadBlockFromHash(blk.hash)
        assertThat(blk.hashCode()).isEqualTo(blk2.hashCode())
    }

    @Test
    fun c1_publish () {
        val host = Host_load("/tmp/freechains/tests/local/")
        val chain = host.createChain("/ceu", false, arrayOf("","",""))
        val n1 = chain.publish("utf8",false, "aaa")
        val n2 = chain.publish("utf8",false, "bbb")
        val n3 = chain.publish("utf8",false, "ccc")

        chain.assertBlock(n3)
        var ok = false
        try {
            val n = n3.copy(hashable = n3.hashable.copy(payload = "xxx"))
            chain.assertBlock(n)
        } catch (e: Throwable) {
            ok = true
        }
        assert(ok)

        assert(chain.containsBlock(chain.toGenHash()))
        //println(n1.toHeightHash())
        assert(chain.containsBlock(n1.hash))
        assert(chain.containsBlock(n2.hash))
        assert(chain.containsBlock(n3.hash))
        assert(!chain.containsBlock("2_........"))
    }

    @Test
    fun d2_proto () {
        val local = Host_load("/tmp/freechains/tests/local/")
        thread { daemon(local) }
        Thread.sleep(100)

        main(arrayOf("host","stop"))
        Thread.sleep(100)
    }

    @Test
    fun d3_proto () {
        //a_reset()

        // SOURCE
        val src = Host_create("/tmp/freechains/tests/src/")
        val src_chain = src.createChain("/d3", false, arrayOf("secret","",""))
        src_chain.publish("utf8",false, "aaa")
        src_chain.publish("utf8",false, "bbb")
        thread { daemon(src) }

        // DESTINY
        val dst = Host_create("/tmp/freechains/tests/dst/", 8331)
        dst.createChain("/d3", false, arrayOf("secret","",""))
        thread { daemon(dst) }
        Thread.sleep(100)

        main(arrayOf("chain","send","/d3","localhost:8331"))
        Thread.sleep(100)

        main(arrayOf("--host=localhost:8331","host","stop"))
        main(arrayOf("host","stop"))
        Thread.sleep(100)

        // TODO: check if dst == src
        // $ diff -r /tmp/freechains/tests/dst/ /tmp/freechains/tests/src/
    }

    @Test
    fun e1_graph () {
        val chain = Chain("/tmp/freechains/tests/local/chains/", "/graph", false, arrayOf("secret","",""))
        chain.save()
        val genesis = Block(
            BlockHashable(0,"utf8", false, "", emptyArray()),
            emptyArray(),"", chain.toGenHash()
        )
        chain.saveBlock(genesis)

        val a1 = chain.newBlock(BlockHashable(0,"utf8", false, "a1", arrayOf(chain.toGenHash())))
        val b1 = chain.newBlock(BlockHashable(0,"utf8", false, "b1", arrayOf(chain.toGenHash())))
        chain.saveBlock(a1)
        chain.saveBlock(b1)
        chain.reheads(a1)
        chain.reheads(b1)

        //val ab2 =
        chain.publish("utf8",false, "ab2")

        val b2 = chain.newBlock(BlockHashable(0,"utf8",false, "b2", arrayOf(b1.hash)))
        chain.saveBlock(b2)
        chain.reheads(b2)

        chain.publish("utf8",false, "ab3")
        chain.save()
        /*
               /-- (a1) --\
        (G) --<            >-- (ab2) --\__ (ab3)
               \-- (b1) --+--- (b2) ---/
         */
    }

    @Test
    fun f1_peers () {
        //a_reset()

        val h1 = Host_create("/tmp/freechains/tests/h1/", 8330)
        val h1_chain = h1.createChain("/xxx", false, arrayOf("","",""))
        h1_chain.publish("utf8",false, "h1_1")
        h1_chain.publish("utf8",false, "h1_2")

        val h2 = Host_create("/tmp/freechains/tests/h2/", 8331)
        val h2_chain = h2.createChain("/xxx", false, arrayOf("","",""))
        h2_chain.publish("utf8",false, "h2_1")
        h2_chain.publish("utf8",false, "h2_2", 0)

        Thread.sleep(100)
        thread { daemon(h1) }
        thread { daemon(h2) }
        Thread.sleep(100)
        main(arrayOf("--host=localhost:8331","chain","send","/xxx","localhost"))
        Thread.sleep(100)
        main(arrayOf("--host=localhost:8331","host","stop"))
        main(arrayOf("host","stop"))
        Thread.sleep(100)

        // TODO: check if 8332 (h2) < 8331 (h1)
        // $ diff -r /tmp/freechains/tests/h1 /tmp/freechains/tests/h2/
    }

    @Test
    fun m1_args () {
        //a_reset()
        main(arrayOf("host","create","/tmp/freechains/tests/M1/"))
        thread {
            main(arrayOf("host","start","/tmp/freechains/tests/M1/"))
        }
        Thread.sleep(100)
        main(arrayOf("chain","join","/xxx"))

        main(arrayOf("chain","genesis","/xxx"))
        main(arrayOf("chain","heads","/xxx"))

        main(arrayOf("chain","put","/xxx","inline","utf8","aaa"))
        main(arrayOf("chain","put","/xxx","file","utf8","/tmp/freechains/tests/M1/host"))

        main(arrayOf("chain","genesis","/xxx"))
        main(arrayOf("chain","heads","/xxx"))

        main(arrayOf("chain","get","--host=localhost:8330","/xxx", "0_87732F8F0B42F1A372BB47F43AF4663D8EAB459486459F096FD34FF73E11BFA0"))
        main(arrayOf("chain","get","/xxx", "0_87732F8F0B42F1A372BB47F43AF4663D8EAB459486459F096FD34FF73E11BFA0"))

        main(arrayOf("chain","put","/xxx","file","base64","/bin/cat"))
        main(arrayOf("host","stop"))
        // TODO: check genesis 2x, "aaa", "host"
        // $ cat /tmp/freechains/tests/M1/chains/xxx/*
    }

    @Test
    fun m2_crypto () {
        //a_reset()
        main(arrayOf("host", "create", "/tmp/freechains/tests/M2/"))
        thread {
            main(arrayOf("host", "start", "/tmp/freechains/tests/M2/"))
        }
        Thread.sleep(100)
        val lazySodium = LazySodiumJava(SodiumJava())
        val kp: KeyPair = lazySodium.cryptoSignKeypair()
        val pk: Key = kp.getPublicKey()
        val sk: Key = kp.getSecretKey()
        assert(lazySodium.cryptoSignKeypair(pk.getAsBytes(), sk.getAsBytes()))
        //println("TSTTST: ${pk.asHexString} // ${sk.asHexString}")
        main(arrayOf("crypto", "create", "shared", "senha secreta"))
        main(arrayOf("crypto", "create", "pubpvt", "senha secreta"))

        val msg = "mensagem secreta"
        val nonce = lazySodium.nonce(SecretBox.NONCEBYTES)
        val key = Key.fromHexString("B07CFFF4BE58567FD558A90CD3875A79E0876F78BB7A94B78210116A526D47A5")
        val encrypted = lazySodium.cryptoSecretBoxEasy(msg, nonce, key)
        //println("nonce=${lazySodium.toHexStr(nonce)} // msg=$encrypted")
        val decrypted = lazySodium.cryptoSecretBoxOpenEasy(encrypted, nonce, key)
        assert(msg == decrypted)
    }

    @Test
    fun m2_crypto_pubpvt () {
        val ls = LazySodiumJava(SodiumJava())
        val bobKp = ls.cryptoBoxKeypair()
        val message = "A super secret message".toByteArray()
        val cipherText =
            ByteArray(message.size + Box.SEALBYTES)
        ls.cryptoBoxSeal(cipherText, message, message.size.toLong(), bobKp.publicKey.asBytes)
        val decrypted = ByteArray(message.size)
        val res = ls.cryptoBoxSealOpen(
            decrypted,
            cipherText,
            cipherText.size.toLong(),
            bobKp.publicKey.asBytes,
            bobKp.secretKey.asBytes
        )

        if (!res) {
            println("Error trying to decrypt. Maybe the message was intended for another recipient?")
        }

        println(String(decrypted)) // Should print out "A super secret message"


        val lazySodium = LazySodiumJava(SodiumJava())
        //val kp = ls.cryptoBoxKeypair()

        val pubed = Key.fromHexString("4EC5AF592D177459D2338D07FFF9A9B64822EF5BE9E9715E8C63965DD2AF6ECB").asBytes
        val pvted = Key.fromHexString("70CFFBAAD1E1B640A77E7784D25C3E535F1E5237264D1B5C38CB2C53A495B3FE4EC5AF592D177459D2338D07FFF9A9B64822EF5BE9E9715E8C63965DD2AF6ECB").asBytes
        val pubcu = ByteArray(Box.CURVE25519XSALSA20POLY1305_PUBLICKEYBYTES)
        val pvtcu = ByteArray(Box.CURVE25519XSALSA20POLY1305_SECRETKEYBYTES)

        assert(lazySodium.convertPublicKeyEd25519ToCurve25519(pubcu,pubed))
        assert(lazySodium.convertSecretKeyEd25519ToCurve25519(pvtcu,pvted))

        val dec1 = "mensagem secreta".toByteArray()
        val enc1 = ByteArray(Box.SEALBYTES + dec1.size)
        lazySodium.cryptoBoxSeal(enc1, dec1, dec1.size.toLong(), pubcu)
        println(LazySodium.toHex(enc1))

        val enc2 = LazySodium.toBin(LazySodium.toHex(enc1))
        println(LazySodium.toHex(enc2))
        assert(Arrays.equals(enc1,enc2))
        val dec2 = ByteArray(enc2.size - Box.SEALBYTES)
        lazySodium.cryptoBoxSealOpen(dec2, enc2, enc2.size.toLong(), pubcu, pvtcu)
        assert(dec2.toString(Charsets.UTF_8) == "mensagem secreta")
    }

    @Test
    fun m3_crypto_publish () {
        //a_reset()
        val host = Host_load("/tmp/freechains/tests/M2/")

        val c1 = host.createChain("/sym", false, arrayOf("64976DF4946F45D6EF37A35D06A1D9A1099768FBBC2B4F95484BA390811C63A2","",""))
        val n1 = c1.publish("utf8",false, "aaa", 0)
        c1.assertBlock(n1)
        var ok1 = false
        try {
            val c = c1.copy(keys=arrayOf("wrong","",""))
            c.assertBlock(n1)       // now I can post w/ the wrong key
        } catch (e: Throwable) {
            ok1 = true
        }
        assert(!ok1)

        val c2 = host.createChain("/asy", false, arrayOf("","3CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322","6F99999751DE615705B9B1A987D8422D75D16F5D55AF43520765FA8C5329F7053CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322"))
        val n2 = c2.publish("utf8",false, "aaa", 0)
        c2.assertBlock(n2)
        val cx = c2.copy(keys=arrayOf("","3CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322",""))
        cx.assertBlock(n2)
        var ok2 = false
        try {
            val cz = c2.copy(keys=arrayOf("","4CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322",""))
            cz.assertBlock(n2)
        } catch (e: Throwable) {
            ok2 = true
        }
        assert(ok2)
    }

    @Test
    fun m4_crypto_encrypt () {
        //a_reset()
        val host = Host_load("/tmp/freechains/tests/M2/")
        val c1 = host.loadChain("/sym")
        val n1 = c1.publish("utf8", true,"aaa", 0)
        val n2 = c1.loadBlockFromHash(n1.hash, true)
        assert(n2.hashable.payload == "aaa")
        //Thread.sleep(500)
    }

    @Test
    fun m5_crypto_encrypt_sym () {
        //a_reset()
        main(arrayOf("host","create","/tmp/freechains/tests/M50/"))
        main(arrayOf("host","create","/tmp/freechains/tests/M51/","8331"))
        thread { main(arrayOf("host","start","/tmp/freechains/tests/M50/")) }
        thread { main(arrayOf("host","start","/tmp/freechains/tests/M51/")) }
        Thread.sleep(100)
        main(arrayOf("chain","join","/xxx","shared","rw","64976DF4946F45D6EF37A35D06A1D9A1099768FBBC2B4F95484BA390811C63A2"))
        main(arrayOf("--host=localhost:8331","chain","join","/xxx","shared","rw","64976DF4946F45D6EF37A35D06A1D9A1099768FBBC2B4F95484BA390811C63A2"))

        main(arrayOf("chain","put","/xxx","inline","utf8","aaa","--encrypt"))
        main(arrayOf("chain","send","/xxx","localhost:8331"))
    }

    @Test
    fun m6_crypto_encrypt_sym () {
        a_reset()
        main(arrayOf("host","create","/tmp/freechains/tests/M60/"))
        main(arrayOf("host","create","/tmp/freechains/tests/M61/","8331"))
        thread { main(arrayOf("host","start","/tmp/freechains/tests/M60/")) }
        thread { main(arrayOf("host","start","/tmp/freechains/tests/M61/")) }
        Thread.sleep(100)
        main(arrayOf("chain","join","/xxx","pubpvt","rw","3CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322","6F99999751DE615705B9B1A987D8422D75D16F5D55AF43520765FA8C5329F7053CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322"))
        main(arrayOf("--host=localhost:8331","chain","join","/xxx","pubpvt","rw","3CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322"))
        val hash = main_(arrayOf("chain","put","/xxx","inline","utf8","aaa","--encrypt"))

        val json = main_(arrayOf("chain","get","/xxx",hash!!))
        val blk = json!!.jsonToBlock()
        assert(blk.hashable.payload == "aaa")

        main(arrayOf("chain","send","/xxx","localhost:8331"))
        val json2 = main_(arrayOf("--host=localhost:8331","chain","get","/xxx",hash))
        val blk2 = json2!!.jsonToBlock()
        assert(blk2.hashable.encrypted)
    }
}

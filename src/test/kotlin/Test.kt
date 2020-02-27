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

/*
 *  TODO:
 *                                reps
 *  -   736 ->   809 ->   930 ->  1180 ->  1131 LOC
 *  - 10553 -> 10555 -> 10557 -> 10568 -> 10575 KB
 *  - chain locks (test sends in parallel)
 *  - reputation system
 *    - quarentine
 *    - new blocks (must work)
 *    - liferea: likes in title
 *    - progressive like costs
 *      - give likes to /dev/null when using 2+
 *    - refuse self likes?
 *  - test --utf8-eof
 *  - all use cases (chain cfg e usos da industria)
 *  - assert time of new >= heads
 *  - save arrays of hashes ordered
 *  - commands with auth. ip port time to avoid reuse
 *  - testes antigos
 *  - RX Kotlin
 *  - pipes / filtros
 *  - freechains host restart
 *  - --ref=<hash> [post] sets back reference to post
 *  - Future:
 *  - Xfreechains
 *    - chain xtraverse
 *  - Android WiFi Direct
 *  - crypto host-to-host
 *  - RPi: cable + router + phones
 */

val H   = BlockHashable(0, null,"",false, "", emptyArray(), emptyArray())
val HC  = H.copy(encoding="utf8", encrypted=true)
val BLK = Block(H,mutableListOf(),null, "")

const val PVT1 = "6F99999751DE615705B9B1A987D8422D75D16F5D55AF43520765FA8C5329F7053CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322"
const val PUB1 = "3CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322"
const val PVT2 = "6A416117B8F7627A3910C34F8B35921B15CF1AC386E9BB20E4B94AF0EDBE24F4E14E4D7E152272D740C3CA4298D19733768DF7E74551A9472AAE384E8AB34369"
const val PUB2 = "E14E4D7E152272D740C3CA4298D19733768DF7E74551A9472AAE384E8AB34369"

@TestMethodOrder(Alphanumeric::class)
class Tests {

    @Test
    fun a_reset () {
        assert( File("/tmp/freechains/tests/").deleteRecursively() )
    }

    /*
    @Test
    fun a1_threads () {
        val c1 = Chain("x", "y", true, arrayOf("x","y"))
        val c2 = Chain("x", "y", true, arrayOf("x","y"))
        thread {
            synchronized(c1.hash) {
                for (i in 1..5) {
                    Thread.sleep(1000)
                    println("thread 1")
                }
            }
        }
        thread {
            synchronized(c2.hash) {
                for (i in 1..5) {
                    Thread.sleep(1000)
                    println("thread 2")
                }
            }
        }
        Thread.sleep(10000)
    }
     */

    @Test
    fun a2_json () {
        @Serializable
        data class MeuDado(val v: String)

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
        //a_reset()
        val h = Host_create("/tmp/freechains/tests/local/")
        val c1 = h.joinChain("/uerj", false, arrayOf("secret","",""))
        //println("Chain /uerj: ${chain1.toHash()}")
        c1.save()

        val c2 = h.loadChain(c1.name)
        assertThat(c1.hashCode()).isEqualTo(c2.hashCode())

        val blk = c2.blockNew("",  H)
        val blk2 = c2.loadBlockFromHash(blk.hash,false)
        assertThat(blk.hashCode()).isEqualTo(blk2.hashCode())
    }

    @Test
    fun c1_post () {
        val host = Host_load("/tmp/freechains/tests/local/")
        val chain = host.joinChain("/", false, arrayOf("","",""))
        val n1 = chain.blockNew("", H)
        val n2 = chain.blockNew("", H)
        val n3 = chain.blockNew("", H)

        var ok = false
        try {
            val n = n3.copy(hashable = n3.hashable.copy(payload="xxx"))
            chain.blockAssert(n)
        } catch (e: Throwable) {
            ok = true
        }
        assert(ok)

        assert(chain.containsBlock(chain.getGenesis()))
        //println(n1.toHeightHash())
        assert(chain.containsBlock(n1.hash))
        assert(chain.containsBlock(n2.hash))
        assert(chain.containsBlock(n3.hash))
        assert(!chain.containsBlock("2_........"))
    }

    @Test
    fun d2_proto () {
        val local = Host_load("/tmp/freechains/tests/local/")
        thread { Daemon(local).daemon() }
        Thread.sleep(100)

        main(arrayOf("host","stop"))
        Thread.sleep(100)
    }

    @Test
    fun d3_proto () {
        a_reset()

        // SOURCE
        val src = Host_create("/tmp/freechains/tests/src/")
        val src_chain = src.joinChain("/d3", false, arrayOf("secret","",""))
        src_chain.blockNew("", H)
        src_chain.blockNew("", H)
        thread { Daemon(src).daemon() }

        // DESTINY
        val dst = Host_create("/tmp/freechains/tests/dst/", 8331)
        dst.joinChain("/d3", false, arrayOf("secret","",""))
        thread { Daemon(dst).daemon() }
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
        a_reset()
        val h = Host_create("/tmp/freechains/tests/graph/")
        val chain = h.joinChain("/", false, arrayOf("secret","",""))

        val a1  = chain.blockNew("", H.copy(time=2*day-1, payload="a1"))
        val b1  = chain.blockNew("", H.copy(time=2*day,   backs=arrayOf(chain.getGenesis())))
        val ab2 = chain.blockNew("", H.copy(time=27*day))
        val b2  = chain.blockNew("", H.copy(time=28*day, backs=arrayOf(b1.hash)))
        chain.blockNew("", H.copy(time=32*day))
        /*
               /-- (a1) --\
        (G) --<            >-- (ab2) --\__ (ab3)
               \-- (b1) --+--- (b2) ---/
         */

        var n = 0
        for (blk in chain.traverseFromHeads{ true }) {
            n++
        }
        assert(n == 6)

        val x = chain.traverseFromHeads { it.height>1 }
        assert(x.size == 3)

        fun Chain.getMaxTime () : Long {
            return this.heads
                .map { this.loadBlockFromHash(it,false) }
                .map { it.hashable.time }
                .max()!!
        }

        val y = chain.traverseFromHeads{ true }.filter { it.hashable.time >= chain.getMaxTime()-30*day }
        println(y.map { it.hash })
        assert(y.size == 4)

        val z = chain.traverseFromHeads(listOf(ab2.hash), { it.hashable.time>1*day })
        assert(z.size == 3)
    }

    @Test
    fun f1_peers () {
        //a_reset()

        val h1 = Host_create("/tmp/freechains/tests/h1/", 8330)
        val h1_chain = h1.joinChain("/xxx", false, arrayOf("","",""))
        h1_chain.blockNew("", H)
        h1_chain.blockNew("", H)

        val h2 = Host_create("/tmp/freechains/tests/h2/", 8331)
        val h2_chain = h2.joinChain("/xxx", false, arrayOf("","",""))
        h2_chain.blockNew("", H)
        h2_chain.blockNew("", H)

        Thread.sleep(100)
        thread { Daemon(h1).daemon() }
        thread { Daemon(h2).daemon() }
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

        main(arrayOf("chain","post","/xxx","inline","utf8","aaa"))
        main(arrayOf("chain","post","/xxx","file","utf8","/tmp/freechains/tests/M1/host"))

        main(arrayOf("chain","genesis","/xxx"))
        main(arrayOf("chain","heads","/xxx"))

        main(arrayOf("chain","get","--host=localhost:8330","/xxx", "0_87732F8F0B42F1A372BB47F43AF4663D8EAB459486459F096FD34FF73E11BFA0"))
        main(arrayOf("chain","get","/xxx", "0_87732F8F0B42F1A372BB47F43AF4663D8EAB459486459F096FD34FF73E11BFA0"))

        main(arrayOf("chain","post","/xxx","file","base64","/bin/cat"))
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
    fun m3_crypto_post () {
        //a_reset()
        //main(arrayOf("host", "create", "/tmp/freechains/tests/M2/"))
        val host = Host_load("/tmp/freechains/tests/M2/")

        val c1 = host.joinChain("/sym", false, arrayOf("64976DF4946F45D6EF37A35D06A1D9A1099768FBBC2B4F95484BA390811C63A2","",""))
        val n1 = c1.blockNew("", H)
        c1.blockAssert(n1)
        var ok1 = false
        try {
            val c = c1.copy(keys=arrayOf("wrong","",""))
            c.blockAssert(n1)       // now I can post w/ the wrong key
        } catch (e: Throwable) {
            ok1 = true
        }
        assert(!ok1)

        val c2 = host.joinChain("/asy", false, arrayOf("",PUB1,PVT1))
        val n2 = c2.blockNew("", H)
        c2.blockAssert(n2)
        val cx = c2.copy(keys=arrayOf("",PUB1,""))
        cx.blockAssert(n2)
        var ok2 = false
        try {
            val cz = c2.copy(keys=arrayOf("","4CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322",""))
            cz.blockAssert(n2)
        } catch (e: Throwable) {
            ok2 = true
        }
        assert(ok2)
    }

    @Test
    fun m4_crypto_encrypt () {
        val host = Host_load("/tmp/freechains/tests/M2/")
        val c1 = host.loadChain("/sym")
        println(c1.root)
        val n1 = c1.blockNew("",HC.copy(payload="aaa"))
        println(n1.hash)
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

        main(arrayOf("chain","post","/xxx","inline","utf8","aaa","--encrypt"))
        main(arrayOf("chain","send","/xxx","localhost:8331"))
    }

    @Test
    fun m6_crypto_encrypt_asy () {
        a_reset() // must be here
        main(arrayOf("host","create","/tmp/freechains/tests/M60/"))
        main(arrayOf("host","create","/tmp/freechains/tests/M61/","8331"))
        thread { main(arrayOf("host","start","/tmp/freechains/tests/M60/")) }
        thread { main(arrayOf("host","start","/tmp/freechains/tests/M61/")) }
        Thread.sleep(100)
        main(arrayOf("chain","join","/xxx","pubpvt","rw",PUB1,PVT1))
        main(arrayOf("--host=localhost:8331","chain","join","/xxx","pubpvt","rw",PUB1))
        val hash = main_(arrayOf("chain","post","/xxx","inline","utf8","aaa","--encrypt"))

        val json = main_(arrayOf("chain","get","/xxx",hash!!))
        val blk = json!!.jsonToBlock()
        assert(blk.hashable.payload == "aaa")

        main(arrayOf("chain","send","/xxx","localhost:8331"))
        val json2 = main_(arrayOf("--host=localhost:8331","chain","get","/xxx",hash))
        val blk2 = json2!!.jsonToBlock()
        assert(blk2.hashable.encrypted)

        val h2 = main_(arrayOf("chain","post","/xxx","inline","utf8","bbb","--sign=$PVT2"))
        val j2 = main_(arrayOf("chain","get","/xxx",h2!!))
        val b2 = j2!!.jsonToBlock()
        assert(b2.hashable.payload == "bbb")
    }

    @Test
    fun m8_likes () {
        a_reset() // must be here
        main(arrayOf("host","create","/tmp/freechains/tests/M80/"))
        thread { main(arrayOf("host","start","/tmp/freechains/tests/M80/")) }
        Thread.sleep(100)
        main(arrayOf("chain","join","/xxx","pubpvt","rw",PUB1,PVT1))

        val h1 = main_(arrayOf("chain","post","/xxx","inline","utf8","aaa","--time=0","--sign=$PVT1"))
        val h2 = main_(arrayOf("chain","post","/xxx","inline","utf8","bbb","--time=0","--sign=$PVT2"))

        //main_(arrayOf("chain","like","/xxx","1",h1!!,"--time="+(24*hour-1).toString(),"--sign=$PVT1"))
        assert("0" == main_(arrayOf("chain","like","get","/xxx",PUB1)))
        assert("0" == main_(arrayOf("chain","like","get","/xxx",PUB2)))

        // give to myself
        main_(arrayOf("chain","like","post","/xxx","1000",h1!!,"--time="+(1*day).toString(),"--sign=$PVT1"))
        assert("500" == main_(arrayOf("--time="+(1*day).toString(),"chain","like","get","/xxx","3CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322")))

        // give to other
        val h3 = main_(arrayOf("chain","like","post","/xxx","1000",h2!!,"--time="+(1*day).toString(),"--sign=$PVT1"))
        assert("0" == main_(arrayOf("--time="+(1*day).toString(),"chain","like","get","/xxx","3CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322")))
        assert("1000" == main_(arrayOf("--time="+(1*day).toString(),"chain","like","get","/xxx",PUB2)))

        main_(arrayOf("chain","like","post","/xxx","1000",h3!!,"--time="+(1*day).toString(),"--why="+h3.substring(0,9),"--sign=$PVT2"))
        main_(arrayOf("chain","like","post","/xxx","1000",h3,"--time="+(1*day+1).toString(),"--why="+h3.substring(0,9),"--sign=$PVT2"))
    }
}

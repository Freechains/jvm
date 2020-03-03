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
 *                                reps             28-02    29-02
 *  -   736 ->   809 ->   930 ->  1180 ->  1131 ->  1365 ->  1434 ->  1453/1366 LOC
 *  - 10553 -> 10555 -> 10557 -> 10568 -> 10575 -> 10590 -> 10607 KB
 *  - Simulation.kt
 *  - HOST: "create" receives pub/pvt args
 *    - creates pvt chain oo (for logs)
 *    - join reputation system (evaluate continue@xxx)
 *    - all conns start with pubs from both ends
 *  - REPUTATION
 *    - lks rewards proportional to childs
 *    - liferea: likes in title // get rep in menu and each post owner
 *  - TEST
 *    - --utf8-eof
 *    - oonly
 *    - fork w/ double spend (second should go to noob list)
 *    - old tests
 *  - REFACTOR
 *    - join (stop/now/flush), they use connection
 *  - CMDS
 *    - freechains now s/ time (retorna now)
 *    - freechains host restart
 *    - freechains chain remove hash (removes block and fronts)
 *    - --ref=<hash> [post] sets back reference to post
 *  - QUARANTINE
 *    - signal remote as soon as local detects the first tine in the chain (to avoid many others in the same chain)
 *    - limit tines per IP
 *    - peek, hold, accept, refuse (blacklist)
 *  - BUGS
 *    - none
 *  - all use cases (chain cfg e usos da industria)
 *  - commands with auth. ip port time to avoid reuse
 *  - RX Kotlin
 *  - pipes / filtros
 *  - Future:
 *  - Xfreechains
 *    - chain xtraverse
 *  - Android WiFi Direct
 *  - crypto host-to-host
 *  - RPi: cable eth + wifi router + phones
 */

val H   = BlockHashable(0, null,"",false, "", emptyArray(), emptyArray())
val HC  = H.copy(encoding="utf8", encrypted=true)
val BLK = Block(H,mutableListOf(),null, "")

const val PVT0 = "6F99999751DE615705B9B1A987D8422D75D16F5D55AF43520765FA8C5329F7053CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322"
const val PUB0 = "3CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322"
const val PVT1 = "6A416117B8F7627A3910C34F8B35921B15CF1AC386E9BB20E4B94AF0EDBE24F4E14E4D7E152272D740C3CA4298D19733768DF7E74551A9472AAE384E8AB34369"
const val PUB1 = "E14E4D7E152272D740C3CA4298D19733768DF7E74551A9472AAE384E8AB34369"

const val H0 = "--host=localhost:8330"
const val H1 = "--host=localhost:8331"

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
        val c1 = h.joinChain("/uerj", Shared("secret"))
        //println("Chain /uerj: ${chain1.toHash()}")
        c1.save()

        val c2 = h.loadChain(c1.name)
        assertThat(c1.hashCode()).isEqualTo(c2.hashCode())

        val blk = c2.blockNew(null, HC)
        val blk2 = c2.loadBlock("blocks", blk.hash,false)
        assertThat(blk.hashCode()).isEqualTo(blk2.hashCode())
    }

    @Test
    fun c1_post () {
        val host = Host_load("/tmp/freechains/tests/local/")
        val chain = host.joinChain("/", null)
        val n1 = chain.blockNew(null,H)
        val n2 = chain.blockNew(null, H)
        val n3 = chain.blockNew(null, H)

        var ok = false
        try {
            val n = n3.copy(hashable = n3.hashable.copy(payload="xxx"))
            chain.blockAssert(n)
        } catch (e: Throwable) {
            ok = true
        }
        assert(ok)

        assert(chain.containsBlock("blocks", chain.getGenesis()))
        //println(n1.toHeightHash())
        assert(chain.containsBlock("blocks", n1.hash))
        assert(chain.containsBlock("blocks", n2.hash))
        assert(chain.containsBlock("blocks", n3.hash))
        assert(!chain.containsBlock("blocks", "2_........"))
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
        val src_chain = src.joinChain("/d3", Shared("secret"))
        src_chain.blockNew(null, HC)
        src_chain.blockNew(null, HC)
        thread { Daemon(src).daemon() }

        // DESTINY
        val dst = Host_create("/tmp/freechains/tests/dst/", 8331)
        dst.joinChain("/d3", Shared("secret"))
        thread { Daemon(dst).daemon() }
        Thread.sleep(100)

        main(arrayOf("chain","send","/d3","localhost:8331"))
        Thread.sleep(100)

        main(arrayOf(H1,"host","stop"))
        main(arrayOf("host","stop"))
        Thread.sleep(100)

        // TODO: check if dst == src
        // $ diff -r /tmp/freechains/tests/dst/ /tmp/freechains/tests/src/
    }

    @Test
    fun e1_graph () {
        a_reset()
        val h = Host_create("/tmp/freechains/tests/graph/")
        val chain = h.joinChain("/", Shared("secret"))

        val ab0 = chain.blockNew(null, HC.copy(time=1*day))
        chain.blockNew(null, HC.copy(time=2*day-1, payload="a1"))
        val b1  = chain.blockNew(null, HC.copy(time=2*day, backs=arrayOf(ab0.hash)))
        val ab2 = chain.blockNew(null, HC.copy(time=27*day))
        chain.blockNew(null, HC.copy(time=28*day, backs=arrayOf(b1.hash)))
        chain.blockNew(null, HC.copy(time=32*day))

        /*
                      /-- (a1) --\
        (G) -- (ab0) <            >-- (ab2) --\
                      \          /             > (ab3)
                       \-- (b1) +---- (b2) ---/
         */

        var n = 0
        for (blk in chain.traverseFromHeads{ true }) {
            n++
        }
        assert(n == 7)

        val x = chain.traverseFromHeads { it.height>2 }
        assert(x.size == 3)

        fun Chain.getMaxTime () : Long {
            return this.heads
                .map { this.loadBlock("blocks", it,false) }
                .map { it.hashable.time }
                .max()!!
        }

        val y = chain.traverseFromHeads{ true }.filter { it.hashable.time >= chain.getMaxTime()-30*day }
        //println(y.map { it.hash })
        assert(y.size == 4)

        val z = chain.traverseFromHeads(listOf(ab2.hash), { it.hashable.time>1*day })
        assert(z.size == 3)
    }

    @Test
    fun f1_peers () {
        //a_reset()

        val h1 = Host_create("/tmp/freechains/tests/h1/", 8330)
        val h1_chain = h1.joinChain("/xxx", null)
        h1_chain.blockNew(null, H)
        h1_chain.blockNew(null, H)

        val h2 = Host_create("/tmp/freechains/tests/h2/", 8331)
        val h2_chain = h2.joinChain("/xxx", null)
        h2_chain.blockNew(null, H)
        h2_chain.blockNew(null, H)

        Thread.sleep(100)
        thread { Daemon(h1).daemon() }
        thread { Daemon(h2).daemon() }
        Thread.sleep(100)
        main(arrayOf(H1,"chain","send","/xxx","localhost"))
        Thread.sleep(100)
        main(arrayOf(H1,"host","stop"))
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

        main(arrayOf("chain","get",H0,"/xxx", "0_87732F8F0B42F1A372BB47F43AF4663D8EAB459486459F096FD34FF73E11BFA0"))
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
        //println(LazySodium.toHex(enc1))

        val enc2 = LazySodium.toBin(LazySodium.toHex(enc1))
        //println(LazySodium.toHex(enc2))
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

        val c1 = host.joinChain("/sym", Shared("64976DF4946F45D6EF37A35D06A1D9A1099768FBBC2B4F95484BA390811C63A2"))
        val n1 = c1.blockNew(null, HC)
        c1.blockAssert(n1)
        var ok1 = false
        try {
            val c = c1.copy(crypto=Shared("wrong"))
            c.blockAssert(n1)       // now I can post w/ the wrong key
        } catch (e: Throwable) {
            ok1 = true
        }
        assert(!ok1)

        val c2 = host.joinChain("/asy", PubPvt(false,PUB0,PVT0))
        val n2 = c2.blockNew("chain", H)
        c2.blockAssert(n2)
        val cx = c2.copy(crypto=PubPvt(false,PUB0,null))
        cx.blockAssert(n2)
    }

    @Test
    fun m4_crypto_encrypt () {
        val host = Host_load("/tmp/freechains/tests/M2/")
        val c1 = host.loadChain("/sym")
        //println(c1.root)
        val n1 = c1.blockNew(null,HC.copy(payload="aaa"))
        //println(n1.hash)
        val n2 = c1.loadBlock("blocks", n1.hash, true)
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
        main(arrayOf("chain","join","/xxx","shared","64976DF4946F45D6EF37A35D06A1D9A1099768FBBC2B4F95484BA390811C63A2"))
        main(arrayOf(H1,"chain","join","/xxx","shared","64976DF4946F45D6EF37A35D06A1D9A1099768FBBC2B4F95484BA390811C63A2"))

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
        main(arrayOf("chain","join","/xxx","pubpvt",PUB0,PVT0))
        main(arrayOf(H1,"chain","join","/xxx","pubpvt",PUB0))
        val hash = main_(arrayOf("chain","post","/xxx","inline","utf8","aaa","--sign=chain","--encrypt"))

        val json = main_(arrayOf("chain","get","/xxx",hash))
        val blk = json.jsonToBlock()
        assert(blk.hashable.payload == "aaa")

        main(arrayOf("chain","send","/xxx","localhost:8331"))
        val json2 = main_(arrayOf(H1,"chain","get","/xxx",hash))
        val blk2 = json2.jsonToBlock()
        assert(blk2.hashable.encrypted)

        val h2 = main_(arrayOf("chain","post","/xxx","inline","utf8","bbbb","--sign=$PVT1"))
        val j2 = main_(arrayOf("chain","get","/xxx",h2))
        val b2 = j2.jsonToBlock()
        assert(b2.hashable.payload == "bbbb")
    }

    @Test
    fun m7_genesis_fork () {
        a_reset()

        main(arrayOf("host","create","/tmp/freechains/tests/M70/"))
        thread { main(arrayOf("host","start","/tmp/freechains/tests/M70/")) }
        main(arrayOf("host","create","/tmp/freechains/tests/M71/","8331"))
        thread { main(arrayOf("host","start","/tmp/freechains/tests/M71/")) }
        Thread.sleep(100)

        main_(arrayOf(H0,"chain","join","/"))
        main_(arrayOf(H0,"chain","post","/","inline","utf8","first","--sign=$PVT0"))
        main_(arrayOf(H1,"chain","join","/"))
        main_(arrayOf(H1,"chain","post","/","inline","utf8","first","--sign=$PVT1"))

        val r0 = main_(arrayOf(H0,"chain","send","/","localhost:8331"))
        val r1 = main_(arrayOf(H1,"chain","send","/","localhost:8330"))
        assert(r0==r1 && r0=="0 / 1")

        val r00 = main_(arrayOf(H0,"chain","like","get","/",PUB0))
        val r01 = main_(arrayOf(H1,"chain","like","get","/",PUB0))
        val r10 = main_(arrayOf(H0,"chain","like","get","/",PUB1))
        val r11 = main_(arrayOf(H1,"chain","like","get","/",PUB1))
        assert(r00.toInt() == 29000)
        assert(r01.toInt() == 0)
        assert(r10.toInt() == 0)
        assert(r11.toInt() == 29000)

        val x0 = main_(arrayOf(H0,"--time=${getNow()+1*day}","chain","like","get","/",PUB0))
        val x1 = main_(arrayOf(H1,"--time=${getNow()+1*day}","chain","like","get","/",PUB1))
        assert(x0.toInt() == 30000)
        assert(x1.toInt() == 30000)
    }

    @Test
    fun m8_likes () {
        a_reset() // must be here
        main(arrayOf("host","create","/tmp/freechains/tests/M80/"))
        thread { main(arrayOf("host","start","/tmp/freechains/tests/M80/")) }
        Thread.sleep(100)
        main(arrayOf("chain","join","/xxx","pubpvt",PUB0,PVT0))

        // first post
        val h1 = main_(arrayOf("chain","post","/xxx","inline","utf8","aaa","--time=0","--sign=chain"))

        // noob post
        val h2 = main_(arrayOf("chain","post","/xxx","inline","utf8","bbba","--time=0","--sign=$PVT1"))

        //main_(arrayOf("chain","like","/xxx","1",h1!!,"--time="+(24*hour-1).toString(),"--sign=$PVT1"))
        assert("30000" == main_(arrayOf("chain","like","get","/xxx",PUB0)))
        assert("0" == main_(arrayOf("chain","like","get","/xxx",PUB1)))

        // give to myself
        assert("30000" == main_(arrayOf("--time="+(1*day).toString(),"chain","like","get","/xxx",PUB0)))
        main_(arrayOf("chain","like","post","/xxx","1000",h1,"--time="+(1*day).toString(),"--sign=$PVT0"))
        assert("29500" == main_(arrayOf("--time="+(1*day).toString(),"chain","like","get","/xxx",PUB0)))

        // give to other
        val h3 = main_(arrayOf("chain","like","post","/xxx","1000",h2,"--time="+(1*day).toString(),"--sign=$PVT0"))
        assert("28500" == main_(arrayOf("--time="+(1*day).toString(),"chain","like","get","/xxx",PUB0)))
        assert("1500" == main_(arrayOf("--time="+(1*day).toString(),"chain","like","get","/xxx",PUB1)))

        val h3_ = h3.split(" ")[0]
        main_(arrayOf("chain","like","post","/xxx","1000-",h3_,"--time="+(1*day+0).toString(),"--why="+h3_.substring(0,9),"--sign=$PVT1"))
        assert("500" == main_(arrayOf("--time="+(1*day).toString(),"chain","like","get","/xxx",PUB1)))
        main_(arrayOf("chain","like","post","/xxx","500",h3_,"--time="+(1*day+1).toString(),"--why="+h3_.substring(0,9),"--sign=$PVT1"))
        assert("0" == main_(arrayOf("--time="+(1*day).toString(),"chain","like","get","/xxx",PUB1)))
        assert("28250" == main_(arrayOf("--time="+(1*day).toString(),"chain","like","get","/xxx",PUB0)))

        main_(arrayOf("host","create","/tmp/freechains/tests/M81/","8331"))
        thread { main_(arrayOf("host","start","/tmp/freechains/tests/M81/")) }
        Thread.sleep(100)
        main_(arrayOf(H1,"chain","join","/xxx","pubpvt",PUB0))

        // I'm in the future, old posts will be refused
        val n1 = main_(arrayOf(H0,"chain","send","/xxx","localhost:8331"))
        assert(n1=="0 / 10")

        // I'm in the past, only the first, second has no reputation
        main_(arrayOf(H1,"host","now","0"))
        val n2 = main_(arrayOf(H0,"chain","send","/xxx","localhost:8331"))
        assert(n2=="1 / 10")

        val tines = main_(arrayOf(H1,"chain","tine","list","/xxx"))
        val t0 = tines.split("\n").let {
            assert(it.size == 1)
            assert(it[0].startsWith("2_"))
            it[0]
        }

        // still the same
        main_(arrayOf(H1,"host","now","${2*hour-100}"))
        //main_(arrayOf(H1,"host","flush"))
        val hs1 = main_(arrayOf(H1,"chain","heads","/xxx"))
        assert(hs1.substring(0,2) == "1_")

        // now ok
        main_(arrayOf(H1,"host","now","${2*hour+100}"))
        main_(arrayOf(H1,"chain","accept","/xxx",t0))
        //main_(arrayOf(H1,"host","flush"))
        val hs2 = main_(arrayOf(H1,"chain","heads","/xxx"))
        assert(hs2.substring(0,2) == "2_")

        // I'm still in the past, only the two first
        main_(arrayOf(H1,"host","now","${1*day-2*hour}"))
        val n3 = main_(arrayOf(H0,"chain","send","/xxx","localhost:8331"))
        assert(n3=="0 / 8")

        // receive all
        main_(arrayOf(H1,"host","now","${1*day}"))
        val n4 = main_(arrayOf(H0,"chain","send","/xxx","localhost:8331"))
        assert(n4=="8 / 8")

        // post w/o reputation
        assert("0" == main_(arrayOf("--time="+(1*day).toString(),"chain","like","get","/xxx",PUB1)))
        val hn = main_(arrayOf(H1,"chain","post","/xxx","inline","utf8","no rep","--time=${1*day}","--sign=$PVT1"))
        val n5 = main_(arrayOf(H1,"chain","send","/xxx","localhost:8330"))
        assert(n5=="0 / 1")

        val hs3 = main_(arrayOf(H0,"chain","heads","/xxx"))
        assert(hs3.substring(0,3) == "10_")

        val ts1 = main_(arrayOf(H0,"chain","tine","list","/xxx"))
        val t1 = ts1.split("\n").let {
            assert(it.size == 1)
            assert(it[0].startsWith("11_"))
            it[0]
        }
        main_(arrayOf(H0,"chain","accept","/xxx",t1))

        // flush after 2h
        main_(arrayOf(H0,"host","now","${1*day+2*hour+1*seg}"))
        //main_(arrayOf(H0,"host","flush"))
        val hs4 = main_(arrayOf(H0,"chain","heads","/xxx"))
        assert(hs4.substring(0,3) == "11_")

        // new post, no rep
        val h4 = main_(arrayOf(H1,"chain","post","/xxx","inline","utf8","no sig"))
        val n6 = main_(arrayOf(H1,"chain","send","/xxx","localhost:8330"))
        assert(n6=="0 / 1")
        main_(arrayOf(H0,"host","now","${1*day+4*hour+2*seg}"))

        //main_(arrayOf(H0,"host","flush"))
        val ts2 = main_(arrayOf(H0,"chain","tine","list","/xxx"))
        val t2 = ts2.split("\n").let {
            assert(it.size == 1)
            assert(it[0].startsWith("12_"))
            it[0]
        }
        val b2 = main_(arrayOf(H0,"chain","tine","get","/xxx",t2)).jsonToBlock()
        assert(b2.hashable.payload == "no sig")
        main_(arrayOf(H0,"chain","accept","/xxx",t2))
        val hs5 = main_(arrayOf(H0,"chain","heads","/xxx"))
        assert(hs5.substring(0,3) == "12_")

        // like post w/o pub
        main_(arrayOf(H1,"chain","like","post","/xxx","1000",h4,"--time="+(1*day).toString(),"--sign=$PVT0"))
        val n7 = main_(arrayOf(H1,"chain","send","/xxx","localhost:8330"))
        //println(n7)
        assert(n7=="1 / 1")
        assert("27750" == main_(arrayOf(H1,"--time="+(1*day).toString(),"chain","like","get","/xxx",PUB0)))
        assert("27750" == main_(arrayOf(H0,"--time="+(1*day).toString(),"chain","like","get","/xxx",PUB0)))

        val ln = main_(arrayOf(H0,"chain","like","get","/xxx",hn))
        val l1 = main_(arrayOf(H0,"chain","like","get","/xxx",h1))
        val l2 = main_(arrayOf(H0,"chain","like","get","/xxx",h2))
        val l3 = main_(arrayOf(H0,"chain","like","get","/xxx",h3))
        val l4 = main_(arrayOf(H0,"chain","like","get","/xxx",h4))
        println("$ln // $l1 // $l2 // $l3 // $l4")
        assert(ln=="0" && l1=="500" && l2=="500" && l3=="0" && l4=="500")
    }
}

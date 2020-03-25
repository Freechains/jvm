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
import java.time.Instant
import java.util.*
import kotlin.concurrent.thread

/*
 *  TODO:
 *                                reps             28-02    29-02    17-03   19-03
 *  -   736 ->   809 ->   930 ->  1180 ->  1131 ->  1365 ->  1434 ->  1598 -> 1681 LOC
 *  - 10553 -> 10555 -> 10557 -> 10568 -> 10575 -> 10590 -> 10607 ->  5691 KB
 *  - Simulation.kt
 *  - liferea, /home, table/shared/trusted column, docs, fred, ppt
 *  - PPT:
 *    - isolate case that depend on the recv order
 *    - blink impossible (fronts rule), once published on top: dislike does not affect removal / parallel pub refused
 *  - PROTO:
 *    - time limits constants
 *    - forks: reject smaller/newest branch
 *    - !ACC <- same author (allow same author to point back to non-accepted block)
 *    - initial cost per 32KB
 *    - prunning (hash of bases, starts with genesis), if they don't match, permanent fork
 *  - HOST: "create" receives pub/pvt args
 *    - creates pvt chain oo (for logs, periodic bcast)
 *    - save CFG in a chain
 *    - join reputation system (evaluate continue@xxx)
 *    - replicate command (all state)
 *    - all conns start with pubs from both ends
 *  - TEST
 *    - --utf8-eof
 *    - oonly
 *    - fork w/ double spend (second should go to noob list)
 *    - old tests
 *  - REFACTOR
 *    - join (stop/now), they use connection
 *    - chain/.* move dir files
 *  - CMDS
 *    - freechains now s/ time (retorna now)
 *    - freechains host restart
 *    - freechains crypto w/o passphrase (to self generate)
 *    - --ref=<hash> [post] sets back reference to post (currently only auto w/ likes)
 *  - REPS
 *    - signal remote as soon as local detects the first rejected in the chain (to avoid many others in the same chain)
 *    - limit rejecteds per IP
 *    - likes in n-depth tree (vs 1-depth)
 *  - VERSIONS
 *    - jvm,android,lua
 *    - remove jar from repo, use github releases
 *  - LIFEREA
 *    - likes in title // get rep in menu and each post owner
 *    - autochain, first post introducing itself (ID/photo?)
 *    - menu:
 *      - dot
 *      - like w/ ui for nick/pub
 *  - IMPL:
 *    - fix getNow() per handle()
 *    - blockAssert: verify symmetric auth
 *    - sends in parallel
 *    - blk/hash variations for all functions (avoid extra blockLoads)
 *  - IDEAS:
 *    - chain for restauration of state in other host holding all necessary commands
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

fun Immut.now (t: Long = getNow()) : Immut {
    return this.copy(time = t)
}
val H   = Immut(0, "",false, "", null, null, emptyArray())
val HC  = H.copy(code="utf8", crypt=true)

const val PVT0 = "6F99999751DE615705B9B1A987D8422D75D16F5D55AF43520765FA8C5329F7053CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322"
const val PUB0 = "3CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322"
const val PVT1 = "6A416117B8F7627A3910C34F8B35921B15CF1AC386E9BB20E4B94AF0EDBE24F4E14E4D7E152272D740C3CA4298D19733768DF7E74551A9472AAE384E8AB34369"
const val PUB1 = "E14E4D7E152272D740C3CA4298D19733768DF7E74551A9472AAE384E8AB34369"
const val SHA0 = "64976DF4946F45D6EF37A35D06A1D9A1099768FBBC2B4F95484BA390811C63A2"

const val H0 = "--host=localhost:8330"
const val H1 = "--host=localhost:8331"
const val S0 = "--sign=$PVT0"
const val S1 = "--sign=$PVT1"

@TestMethodOrder(Alphanumeric::class)
class Tests {

    @Test
    fun a_reset() {
        assert(File("/tmp/freechains/tests/").deleteRecursively())
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
    fun a2_json() {
        @Serializable
        data class MeuDado(val v: String)

        val bs: MutableList<Byte> = mutableListOf()
        for (i in 0..255) {
            bs.add(i.toByte())
        }
        val x = bs.toByteArray().toString(Charsets.ISO_8859_1)
        //println(x)
        val s = MeuDado(x)
        //println(s)
        @UseExperimental(UnstableDefault::class)
        val json = Json(JsonConfiguration(prettyPrint = true))
        val a = json.stringify(MeuDado.serializer(), s)
        val b = json.parse(MeuDado.serializer(), a)
        val c = b.v.toByteArray(Charsets.ISO_8859_1)
        assert(bs.toByteArray().contentToString() == c.contentToString())
    }

    @Test
    fun b1_chain() {
        //a_reset()
        val h = Host_create("/tmp/freechains/tests/local/")
        val c1 = h.joinChain("/uerj", false, null)

        val c2 = h.loadChain(c1.name)
        assertThat(c1.hashCode()).isEqualTo(c2.hashCode())

        val blk = c2.blockNew(HC.now(), null, null)
        val blk2 = c2.fsLoadBlock(blk.hash, null)
        assertThat(blk.hashCode()).isEqualTo(blk2.hashCode())
    }

    @Test
    fun c1_post() {
        val host = Host_load("/tmp/freechains/tests/local/")
        val chain = host.joinChain("/", false, ChainPub(false,PUB0))
        val n1 = chain.blockNew(H.now(), PVT0, null)
        val n2 = chain.blockNew(H.now(), PVT0, null)
        val n3 = chain.blockNew(H.now(), null, null)

        var ok = false
        try {
            val n = n3.copy(immut = n3.immut.copy(payload = "xxx"))
            chain.blockAssert(n)
        } catch (e: Throwable) {
            ok = true
        }
        assert(ok)

        assert(chain.fsExistsBlock(chain.getGenesis()))
        //println(n1.toHeightHash())
        assert(chain.fsExistsBlock(n1.hash))
        assert(chain.fsExistsBlock(n2.hash))
        assert(chain.fsExistsBlock(n3.hash))
        assert(!chain.fsExistsBlock("2_........"))
    }

    @Test
    fun d2_proto() {
        val local = Host_load("/tmp/freechains/tests/local/")
        thread { Daemon(local).daemon() }
        Thread.sleep(100)

        main(arrayOf("host", "stop"))
        Thread.sleep(100)
    }

    @Test
    fun d3_proto() {
        a_reset()

        // SOURCE
        val src = Host_create("/tmp/freechains/tests/src/")
        val srcChain = src.joinChain("/d3", false, ChainPub(false,PUB1))
        srcChain.blockNew(HC.now(), PVT1, null)
        srcChain.blockNew(HC.now(), PVT1, null)
        thread { Daemon(src).daemon() }

        // DESTINY
        val dst = Host_create("/tmp/freechains/tests/dst/", 8331)
        dst.joinChain("/d3", false, null)
        thread { Daemon(dst).daemon() }
        Thread.sleep(100)

        main(arrayOf("chain", "send", "/d3", "localhost:8331"))
        Thread.sleep(100)

        main(arrayOf(H1, "host", "stop"))
        main(arrayOf("host", "stop"))
        Thread.sleep(100)

        // TODO: check if dst == src
        // $ diff -r /tmp/freechains/tests/dst/ /tmp/freechains/tests/src/
    }

    @Test
    fun e1_graph() {
        a_reset()
        val h = Host_create("/tmp/freechains/tests/graph/")
        val chain = h.joinChain("/", true, ChainPub(false,PUB0))

        setNow(1*day)
        val ab0 = chain.blockNew(HC.now(1*day), PVT0, null)
        setNow(2*day)
        chain.blockNew(HC.now(2*day-1).copy(payload = "a1"), PVT0, null)
        val b1 = chain.blockNew(HC.now().copy(backs=arrayOf(ab0.hash)), PVT1, null)
        setNow(27*day)
        val ab2 = chain.blockNew(HC.now(), PVT0, null)
        setNow(28*day)
        //assert(chain.blockState(b1) == BlockState.REJECTED)
        chain.blockNew(HC.now().copy(backs = arrayOf(b1.hash)), PVT1, null)
        setNow(32*day)
        chain.blockNew(HC.now(), PVT0, null)

        /*
                      /-- (a1) --\
        (G) -- (ab0) <            >-- (ab2) --\
                      \          /             > (ab3)
                       \-- (b1) +---- (b2) ---/
         */

        assert(chain.bfsAll().size == 7)

        val x = chain.bfs(chain.getHeads(State.ALL),false) { it.hash.toHeight() > 2 }
        assert(x.size == 3)

        fun Chain.getMaxTime(): Long {
            return this.getHeads(State.ALL)
                .map { this.fsLoadBlock(it, null) }
                .map { it.immut.time }
                .max()!!
        }

        val y = chain.bfsAll().filter { it.immut.time >= chain.getMaxTime() - 30 * day }
        //println(y.map { it.hash })
        assert(y.size == 4)

        val z = chain.bfs(listOf(ab2.hash),false) { it.immut.time > 1 * day }
        assert(z.size == 3)
    }

    @Test
    fun f1_peers() {
        //a_reset()

        val h1 = Host_create("/tmp/freechains/tests/h1/", 8330)
        val h1Chain = h1.joinChain("/xxx", false, ChainPub(false,PUB1))
        h1Chain.blockNew(H, PVT1, null)
        h1Chain.blockNew(H, PVT1, null)

        val h2 = Host_create("/tmp/freechains/tests/h2/", 8331)
        val h2Chain = h2.joinChain("/xxx", false, null)
        h2Chain.blockNew(H, PVT1, null)
        h2Chain.blockNew(H, PVT1, null)

        Thread.sleep(100)
        thread { Daemon(h1).daemon() }
        thread { Daemon(h2).daemon() }
        Thread.sleep(100)
        main(arrayOf(H1, "chain", "send", "/xxx", "localhost"))
        Thread.sleep(100)
        main(arrayOf(H1, "host", "stop"))
        main(arrayOf("host", "stop"))
        Thread.sleep(100)

        // TODO: check if 8332 (h2) < 8331 (h1)
        // $ diff -r /tmp/freechains/tests/h1 /tmp/freechains/tests/h2/
    }

    @Test
    fun m01_args() {
        a_reset()
        main(arrayOf("host", "create", "/tmp/freechains/tests/M1/"))
        thread {
            main(arrayOf("host", "start", "/tmp/freechains/tests/M1/"))
        }
        Thread.sleep(100)
        main(arrayOf("chain", "join", "/xxx"))

        assert(main_(arrayOf("chain", "genesis", "/xxx")).startsWith("0_"))
        assert(main_(arrayOf("chain", "heads", "accepted", "/xxx")).startsWith("0_"))

        main_(arrayOf("chain", "post", "/xxx", "inline", "utf8", "aaa", S0))
        assert(main_(arrayOf("chain", "heads", "accepted", "/xxx")).startsWith("1_"))
        main_(arrayOf("chain", "heads", "rejected", "/xxx")).let {
            assert(it.isEmpty())
        }
        main_(arrayOf("chain", "heads", "pending", "/xxx")).let { list ->
            list.split(' ').toTypedArray().let {
                assert(it.size == 1)
                assert(it[0].startsWith("1_"))
            }
        }

        /*val h2 =*/ main_(arrayOf(S0, "chain", "post", "/xxx", "file", "utf8", "/tmp/freechains/tests/M1/host"))

        // h0 -> h1 -> h2

        assert(main_(arrayOf("chain", "heads", "accepted", "/xxx")).startsWith("1_"))
        assert(main_(arrayOf("chain", "heads", "pending",  "/xxx")).startsWith("2_"))
        assert(main_(arrayOf("chain", "heads", "rejected", "/xxx")).isEmpty())

        main(arrayOf("chain", "post", "/xxx", "file", "base64", "/bin/cat"))
        main(arrayOf("host", "stop"))
        // TODO: check genesis 2x, "aaa", "host"
        // $ cat /tmp/freechains/tests/M1/chains/xxx/blocks/*
    }

    @Test
    fun m02_crypto() {
        //a_reset()
        main(arrayOf("host", "create", "/tmp/freechains/tests/M2/"))
        thread {
            main(arrayOf("host", "start", "/tmp/freechains/tests/M2/"))
        }
        Thread.sleep(100)
        val lazySodium = LazySodiumJava(SodiumJava())
        val kp: KeyPair = lazySodium.cryptoSignKeypair()
        val pk: Key = kp.publicKey
        val sk: Key = kp.secretKey
        assert(lazySodium.cryptoSignKeypair(pk.asBytes, sk.asBytes))
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
    fun m02_crypto_pubpvt() {
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
        val pvted =
            Key.fromHexString("70CFFBAAD1E1B640A77E7784D25C3E535F1E5237264D1B5C38CB2C53A495B3FE4EC5AF592D177459D2338D07FFF9A9B64822EF5BE9E9715E8C63965DD2AF6ECB")
                .asBytes
        val pubcu = ByteArray(Box.CURVE25519XSALSA20POLY1305_PUBLICKEYBYTES)
        val pvtcu = ByteArray(Box.CURVE25519XSALSA20POLY1305_SECRETKEYBYTES)

        assert(lazySodium.convertPublicKeyEd25519ToCurve25519(pubcu, pubed))
        assert(lazySodium.convertSecretKeyEd25519ToCurve25519(pvtcu, pvted))

        val dec1 = "mensagem secreta".toByteArray()
        val enc1 = ByteArray(Box.SEALBYTES + dec1.size)
        lazySodium.cryptoBoxSeal(enc1, dec1, dec1.size.toLong(), pubcu)
        //println(LazySodium.toHex(enc1))

        val enc2 = LazySodium.toBin(LazySodium.toHex(enc1))
        //println(LazySodium.toHex(enc2))
        assert(Arrays.equals(enc1, enc2))
        val dec2 = ByteArray(enc2.size - Box.SEALBYTES)
        lazySodium.cryptoBoxSealOpen(dec2, enc2, enc2.size.toLong(), pubcu, pvtcu)
        assert(dec2.toString(Charsets.UTF_8) == "mensagem secreta")
    }

    @Test
    fun m03_crypto_post() {
        //a_reset()
        //main(arrayOf("host", "create", "/tmp/freechains/tests/M2/"))
        val host = Host_load("/tmp/freechains/tests/M2/")
        val c1 = host.joinChain("/sym", false, null)
        c1.blockNew(HC, null, null)
        val c2 = host.joinChain("/asy", false, ChainPub(false, PUB0))
        c2.blockNew(H, PVT0, PVT0)
    }

    @Test
    fun m04_crypto_encrypt() {
        val host = Host_load("/tmp/freechains/tests/M2/")
        val c1 = host.loadChain("/sym")
        //println(c1.root)
        val n1 = c1.blockNew(HC.copy(payload = "aaa"), null, SHA0)
        //println(n1.hash)
        val n2 = c1.fsLoadBlock(n1.hash, SHA0)
        assert(n2.immut.payload == "aaa")
        //Thread.sleep(500)
    }

    @Test
    fun m05_crypto_encrypt_sym() {
        //a_reset()
        main(arrayOf("host", "create", "/tmp/freechains/tests/M50/"))
        main(arrayOf("host", "create", "/tmp/freechains/tests/M51/", "8331"))
        thread { main(arrayOf("host", "start", "/tmp/freechains/tests/M50/")) }
        thread { main(arrayOf("host", "start", "/tmp/freechains/tests/M51/")) }
        Thread.sleep(100)
        main(
            arrayOf (
                "chain",
                "join",
                "/xxx"
            )
        )
        main(
            arrayOf (
                H1,
                "chain",
                "join",
                "/xxx"
            )
        )

        main(arrayOf("chain", "post", "/xxx", "inline", "utf8", "aaa", "--crypt=$SHA0"))
        main(arrayOf("chain", "send", "/xxx", "localhost:8331"))
    }

    @Test
    fun m06_crypto_encrypt_asy() {
        a_reset() // must be here
        main(arrayOf("host", "create", "/tmp/freechains/tests/M60/"))
        main(arrayOf("host", "create", "/tmp/freechains/tests/M61/", "8331"))
        thread { main(arrayOf("host", "start", "/tmp/freechains/tests/M60/")) }
        thread { main(arrayOf("host", "start", "/tmp/freechains/tests/M61/")) }
        Thread.sleep(100)
        main(arrayOf("chain", "join", "/xxx", PUB0))
        main(arrayOf(H1, "chain", "join", "/xxx", PUB0))
        val hash = main_(arrayOf("chain", "post", "/xxx", "inline", "utf8", "aaa", S0, "--crypt=$PVT0"))

        val json = main_(arrayOf("chain", "get", "/xxx", hash, "--crypt=$PVT0"))
        val blk = json.jsonToBlock()
        assert(blk.immut.payload == "aaa")

        main(arrayOf("chain", "send", "/xxx", "localhost:8331"))
        val json2 = main_(arrayOf(H1, "chain", "get", "/xxx", hash))
        val blk2 = json2.jsonToBlock()
        assert(blk2.immut.crypt)

        val h2 = main_(arrayOf("chain", "post", "/xxx", "inline", "utf8", "bbbb", S1))
        val j2 = main_(arrayOf("chain", "get", "/xxx", h2))
        val b2 = j2.jsonToBlock()
        assert(b2.immut.payload == "bbbb")
    }

    @Test
    fun m07_genesis_fork() {
        a_reset()

        main(arrayOf("host", "create", "/tmp/freechains/tests/M70/"))
        thread { main(arrayOf("host", "start", "/tmp/freechains/tests/M70/")) }
        main(arrayOf("host", "create", "/tmp/freechains/tests/M71/", "8331"))
        thread { main(arrayOf("host", "start", "/tmp/freechains/tests/M71/")) }
        Thread.sleep(100)

        main_(arrayOf(H0, "chain", "join", "/"))
        main_(arrayOf(H0, "chain", "post", "/", "inline", "utf8", "first-0", S0))
        main_(arrayOf(H1, "chain", "join", "/"))
        main_(arrayOf(H1, "chain", "post", "/", "inline", "utf8", "first-1", S1))
        Thread.sleep(100)

        // H0: g <- f0
        // H1: g <- f1

        val r0 = main_(arrayOf(H0, "chain", "send", "/", "localhost:8331"))
        val r1 = main_(arrayOf(H1, "chain", "send", "/", "localhost:8330"))
        assert(r0 == r1 && r0 == "0 / 1")

        val r00 = main_(arrayOf(H0, "chain", "reps", "/", PUB0))
        val r11 = main_(arrayOf(H1, "chain", "reps", "/", PUB1))
        val r10 = main_(arrayOf(H0, "chain", "reps", "/", PUB1))
        val r01 = main_(arrayOf(H1, "chain", "reps", "/", PUB0))
        assert(r00.toInt() == 29)
        assert(r11.toInt() == 29)
        assert(r10.toInt() == 0)
        assert(r01.toInt() == 0)

        main_(arrayOf(H0, "host", "now", (getNow() + 1*day).toString()))
        main_(arrayOf(H1, "host", "now", (getNow() + 1*day).toString()))

        val x0 = main_(arrayOf(H0, "chain", "reps", "/", PUB0))
        val x1 = main_(arrayOf(H1, "chain", "reps", "/", PUB1))
        assert(x0.toInt() == 30)
        assert(x1.toInt() == 30)
    }

    @Test
    fun m08_likes() {
        a_reset() // must be here
        main(arrayOf("host", "create", "/tmp/freechains/tests/M80/"))
        thread { main(arrayOf("host", "start", "/tmp/freechains/tests/M80/")) }
        Thread.sleep(100)
        main(arrayOf("chain", "join", "/xxx", PUB0))

        main_(arrayOf(H0, "host", "now", "0"))

        val h11 = main_(arrayOf("chain", "post", "/xxx", "inline", "utf8", "h11", S0))
        val h22 = main_(arrayOf("chain", "post", "/xxx", "inline", "utf8", "h22", S1))
        /*val h21 =*/ main_(arrayOf("chain", "post", "/xxx", "inline", "utf8", "h11", S0))

        // h0 -> h11 -> h21
        //          \-> h22

        main_(arrayOf("chain", "heads", "pending",  "/xxx")).let { str ->
            str.split(' ').let {
                assert(it.size == 1)
            }
            assert(str.startsWith("2_"))
        }

        main_(arrayOf("chain", "like", "/xxx", h22, S0)) // l3

        // h0 -> h11 -> h21 -> l3
        //          \-> h22

        main_(arrayOf("chain", "heads", "pending",  "/xxx")).let { str ->
            str.split(' ').let {
                assert(it.size == 2)
                assert(it[0].startsWith("3_") || it[1].startsWith("3_"))
            }
        }
        assert( "0" == main_(arrayOf("chain", "reps", "/xxx", PUB1)))

        main_(arrayOf(H0, "host", "now", (3*hour).toString()))
        /*val h41 =*/ main_(arrayOf("chain", "post", "/xxx", "inline", "utf8", "ok", S0))

        // h0 -> h11 -> h21 -> l3 -> h41
        //          \-> h22 ------/

        main_(arrayOf(H0, "host", "now", (1*day+4*hour).toString()))

        assert(main_(arrayOf("chain", "heads", "accepted", "/xxx")).startsWith("4_"))
        assert("29" == main_(arrayOf("chain", "reps", "/xxx", PUB0)))
        assert( "2" == main_(arrayOf("chain", "reps", "/xxx", PUB1)))

        // like myself
        main_(arrayOf("chain", "like", "/xxx", h11, S0)).let {
            assert(it == "like must not target itself")
        }

        val l5 = main_(arrayOf("chain", "like", "/xxx", h22, S0)) // l5

        // h0 -> h11 -> h21 -> l3 -> h41 -> l5
        //          \-> h22 ------/

        main_(arrayOf("chain", "reps", "/xxx", PUB0)).let {
            assert(it == "28")
        }
        main_(arrayOf("chain", "reps", "/xxx", PUB1)).let {
            assert(it == "3")
        }

        main_(arrayOf(H0, "chain", "heads", "pending", "/xxx")).let { str ->
            str.split(' ').let {
                assert(it.size == 1)
            }
            assert(str.contains("5_"))
        }

        /*val l6 =*/ main_(arrayOf("chain","dislike","/xxx",l5,"--why=" + l5.substring(0,9),S1))

        // h0 <- h11 <- h21 <- l3 <- h41 <- l5
        //          \               /   \     \
        //           \- h22 <----------------- l6

        main_(arrayOf("chain", "reps", "/xxx", PUB1)).let {
            assert(it == "2")
        }
        main_(arrayOf("chain", "reps", "/xxx", PUB0)).let {
            assert(it == "27")
        }

        main_(arrayOf("host", "create", "/tmp/freechains/tests/M81/", "8331"))
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M81/")) }
        Thread.sleep(100)
        main_(arrayOf(H1, "chain", "join", "/xxx", PUB0))

        // I'm in the future, old posts will be refused
        main_(arrayOf(H1, "host", "now", Instant.now().toEpochMilli().toString()))

        val n1 = main_(arrayOf(H0, "chain", "send", "/xxx", "localhost:8331"))
        assert(n1 == "0 / 7")

        main_(arrayOf(H0, "chain", "heads", "pending", "/xxx")).let { str ->
            str.split(' ').let {
                assert(it.size == 1)
            }
            assert(str.contains("6_"))
        }

        // only very old (H1/H2/L3)
        main_(arrayOf(H1, "host", "now", "0"))
        val n2 = main_(arrayOf(H0, "chain", "send", "/xxx", "localhost:8331"))
        assert(n2 == "4 / 7") { n2 }
        main_(arrayOf(H1, "chain", "heads", "accepted", "/xxx")).let {
            assert(it.startsWith("3_"))
        }

        // h0 <- h11 <- h21 <- l3
        //          \
        //           \- h22

        // still the same
        main_(arrayOf(H1, "host", "now", "${2*hour}"))
        main_(arrayOf(H0, "chain", "send", "/xxx", "localhost:8331")).let {
            assert(it == "0 / 3")
        }
        assert("0" == main_(arrayOf("chain", "reps", "/xxx", PUB1)))

        // now ok
        main_(arrayOf(H1, "host", "now", "${1*day + 4*hour + 100}"))
        main_(arrayOf(H0, "chain", "send", "/xxx", "localhost:8331")).let {
            assert(it == "3 / 3")
        }
        main_(arrayOf(H1, "chain", "heads", "accepted", "/xxx")).let {
            assert(it.startsWith("6_"))
        }

        // h0 <- h11 <- h21 <- l3 <- h41 <- l5
        //          \               /         \
        //           \- h22 <-------           l6

        assert("2" == main_(arrayOf("chain", "reps", "/xxx", PUB1)))
        val h7 = main_(arrayOf(H1, "chain", "post", "/xxx", "inline", "utf8", "no rep", S1))

        // h0 <- h11 <- h21 <- l3 <- h41 <- l5
        //          \               /   \     \
        //           \- h22 <----------------- l6 <- h7

        main_(arrayOf(H1, "chain", "send", "/xxx", "localhost:8330")).let {
            assert(it == "1 / 1")
        }
        main_(arrayOf(H1, "chain", "heads", "pending", "/xxx")).let {
            assert(it.startsWith("7_"))
        }

        main_(arrayOf(H1,"chain","like","/xxx",h7,S0))

        // h0 <- h11 <- h21 <- l3 <- h41 <- l5       l7
        //          \               /   \     \   /
        //           \- h22 <----------------- l6 <- h7

        main_(arrayOf(H1, "chain", "heads", "pending", "/xxx")).let { str ->
            str.split(' ').let {
                assert(it.size == 2)
                it.forEach {
                    assert(it.startsWith("7_"))
                }
            }
        }

        main_(arrayOf(H1, "chain", "send", "/xxx", "localhost:8330")).let {
            assert(it == "1 / 1")
        }

        // flush after 2h
        main_(arrayOf(H0, "host", "now", "${1*day + 7*hour}"))
        main_(arrayOf(H1, "host", "now", "${1*day + 7*hour}"))

        main_(arrayOf(H0, "chain", "heads", "accepted", "/xxx")).let { str ->
            str.split(' ').let {
                assert(it.size == 2)
                it.forEach {
                    assert(it.startsWith("7_"))
                }
            }
        }
        main_(arrayOf(H1, "chain", "heads", "accepted", "/xxx")).let { str ->
            str.split(' ').let {
                assert(it.size == 2)
                it.forEach {
                    assert(it.startsWith("7_"))
                }
            }
        }

        // new post, no rep
        val h8 = main_(arrayOf(H1, "chain", "post", "/xxx", "inline", "utf8", "no sig"))
        main_(arrayOf(H1, "chain", "send", "/xxx", "localhost:8330")).let {
            assert(it.equals("0 / 0"))
        }

        // h0 <- h11 <- h21 <- l3 <- h41 <- l5       l7
        //          \               /   \     \   /    \
        //           \- h22 <----------------- l6 <- h7 \
        //                                             \- h8

        main_(arrayOf(H1, "chain", "heads", "rejected", "/xxx")).let {
            assert(it.startsWith("8_"))
        }

        main_(arrayOf(H1, "chain", "get", "/xxx", h8))
            .jsonToBlock()
            .immut.payload
            .let {
                assert(it == "no sig")
            }

        // like post w/o pub
        main_(arrayOf(H1,"chain","like","/xxx",h8,S0))

        // h0 <- h11 <- h21 <- l3 <- h41 <- l5       l7 <- l8
        //          \               /   \     \   /    \/
        //           \- h22 <----------------- l6 <- h7 \
        //                                             \- h8

        main_(arrayOf(H1, "chain", "send", "/xxx", "localhost:8330")).let {
            assert (it == "2 / 2")
        }
        main_(arrayOf(H1, "chain", "reps", "/xxx", PUB0)).let {
            assert(it == "25")
        }

        main_(arrayOf(H1, "host", "now", "${1*day + 10*hour}"))

        main_(arrayOf(H1, "chain", "reps", "/xxx", PUB0)).let {
            assert(it == "25")
        }
        main_(arrayOf(H0, "chain", "reps", "/xxx", PUB0)).let {
            assert(it == "25")
        }

        val ln = main_(arrayOf(H0, "chain", "reps", "/xxx", h7))
        val l1 = main_(arrayOf(H0, "chain", "reps", "/xxx", h11))
        val l2 = main_(arrayOf(H0, "chain", "reps", "/xxx", h22))
        val l3 = main_(arrayOf(H0, "chain", "reps", "/xxx", l5))
        val l4 = main_(arrayOf(H0, "chain", "reps", "/xxx", h8))
        println("$ln // $l1 // $l2 // $l3 // $l4")
        assert(ln == "1" && l1 == "0" && l2 == "2" && l3 == "-1" && l4 == "1")
    }

    @Test
    fun m09_ban() {
        a_reset()

        main(arrayOf("host", "create", "/tmp/freechains/tests/M90/"))
        thread { main(arrayOf("host", "start", "/tmp/freechains/tests/M90/")) }
        Thread.sleep(100)
        main(arrayOf("chain", "join", "/"))

        val h1 = main_(arrayOf(H0, "chain", "post", "/", "inline", "utf8", "h1", S0))
        val h21 = main_(arrayOf(H0, "chain", "post", "/", "inline", "utf8", "h21"))
        main_(arrayOf(H0, "chain", "heads", "rejected", "/")).let {
            assert(it.contains(h21) && !it.contains(h1))
        }

        main_(arrayOf(H0, "chain", "ban", "/", "xxx")).let {
            assert(it == "false")
        }
        main_(arrayOf(H0, "chain", "ban", "/", h21)).let {
            assert(it == "true")
        }
        main_(arrayOf(H0, "chain", "heads", "pending", "/")).let {
            assert(it.contains(h1) && !it.contains(h21))
        }

        // h0 <- h1
        //         \- (h21)

        val h22 = main_(arrayOf(H0, "chain", "post", "/", "inline", "utf8", "h22"))
        assert(h22.startsWith("2_"))
        val l2 = main_(arrayOf(H0,"chain","like","/",h22,S0))

        //            /- h22
        // h0 <- h1 <--- l2
        //           \- (h21)

        val h23 = main_(arrayOf(H0, "chain", "post", "/", "inline", "utf8", "h23"))

        //            /- h23
        //           /-- h22
        // h0 <- h1 <--- l2
        //           \-- (h21)

        main_(arrayOf(H0, "chain", "heads", "rejected", "/")).let {
            assert(it.contains(h23) && !it.contains(h21))
        }

        main_(arrayOf(H0, "chain", "ban", "/", h22)).let {
            assert(it == "true")
        }

        //            /- h23
        //           /-- (h22)
        // h0 <- h1 <--- l2
        //           \-- (h21)

        main_(arrayOf(H0, "chain", "heads", "pending", "/")).let {
            assert(it.contains(l2) && !it.contains(h22))
        }

        // h1 -> h23
        // h22 -> l1
        // h21

        main_(arrayOf(H0, "chain", "unban", "/", h22)).let {
            assert(it == "true")
        }
        main_(arrayOf(H0, "chain", "heads", "rejected", "/")).let {
            assert(!it.contains(h22))
        }
        main_(arrayOf(H0, "chain", "heads", "pending", "/")).let {
            assert(it.contains(l2))
        }
        main_(arrayOf(H0, "chain", "unban", "/", l2)).let {
            assert(it == "false")
        }

        // h1 -> h22 -> l1
        //    -> h23
        // h21

        main_(arrayOf(H0, "chain", "unban", "/", h21)).let {
            assert(it == "true")
        }
        main_(arrayOf(H0, "chain", "heads", "rejected", "/")).let {
            assert(!it.contains(h1) && it.contains(h21) && !it.contains(h22) && it.contains(h23))
        }

        // h1 -> h22 -> l1
        //    -> h21
        //    -> h23

        main_(arrayOf(H0, "chain", "unban", "/", h23)).let {
            assert(it == "false")
        }
        main_(arrayOf(H0, "chain", "heads", "rejected", "/")).let {
            assert(!it.contains(h1) && it.contains(h21) && !it.contains(h22) && it.contains(h23))
        }

        // h1 -> h22 -> l1
        //    -> h21
        //    -> h23
    }

    @Test
    fun m10_cons() {
        a_reset()

        main(arrayOf("host", "create", "/tmp/freechains/tests/M100/"))
        thread { main(arrayOf("host", "start", "/tmp/freechains/tests/M100/")) }
        main(arrayOf("host", "create", "/tmp/freechains/tests/M101/", "8331"))
        thread { main(arrayOf("host", "start", "/tmp/freechains/tests/M101/")) }
        Thread.sleep(100)
        main(arrayOf(H0, "chain", "join", "/", PUB0))
        main(arrayOf(H1, "chain", "join", "/", PUB0))
        main(arrayOf(H0, "host", "now", "0"))
        main(arrayOf(H1, "host", "now", "0"))

        val h1 = main_(arrayOf(H0, "chain", "post", "/", "inline", "utf8", "h1", S1))
        val h2 = main_(arrayOf(H0, "chain", "post", "/", "inline", "utf8", "h2", S1))
        val hx = main_(arrayOf(H0, "chain", "post", "/", "inline", "utf8", "hx", S0))

        // h1 <- h2 (p)
        //   \-- hx (a)

        val as1 = main_(arrayOf(H0, "chain", "heads", "accepted", "/"))
        val ps1 = main_(arrayOf(H0, "chain", "heads", "pending",  "/"))
        val rs1 = main_(arrayOf(H0, "chain", "heads", "rejected", "/"))
        assert(!as1.contains(h1) && !as1.contains(h2) &&  as1.contains(hx))
        assert(!ps1.contains(h1) &&  ps1.contains(h2) &&  ps1.contains(hx))
        assert(!rs1.contains(h1) && !rs1.contains(h2) && !rs1.contains(hx))

        main_(arrayOf(H0, "chain", "send", "/", "localhost:8331"))

        main_(arrayOf(H1, "chain", "post", "/", "inline", "utf8", "h3",S1)).let {
            assert(it == "must point to author's previous post")
        }

        main_(arrayOf(H1, "host", "now", "${3*hour}"))

        val h3 = main_(arrayOf(H1, "chain", "post", "/", "inline", "utf8", "h3",S1))
        assert(h3.startsWith("3_"))

        // h1 (g) <- h2 (a) <- h3 (p)
        //      \ <- hx (a) <-/

        main_(arrayOf(H0, "chain", "send", "/", "localhost:8331"))
        main_(arrayOf(H1, "host", "now", "${6*hour}"))

        main_(arrayOf(H1, "chain", "post", "/", "inline", "utf8", "h4")).let {
            assert(it.startsWith("4_"))
        }
    }

    @Test
    fun m11_send_after_tine() {
        a_reset()

        main(arrayOf("host", "create", "/tmp/freechains/tests/M100/"))
        thread { main(arrayOf("host", "start", "/tmp/freechains/tests/M100/")) }
        main(arrayOf("host", "create", "/tmp/freechains/tests/M101/", "8331"))
        thread { main(arrayOf("host", "start", "/tmp/freechains/tests/M101/")) }
        Thread.sleep(100)
        main(arrayOf(H0, "chain", "join", "/"))
        main(arrayOf(H1, "chain", "join", "/"))

        main_(arrayOf(H0, "chain", "post", "/", "inline", "utf8", "h1",S0))
        val h2 = main_(arrayOf(H0, "chain", "post", "/", "inline", "utf8", "h2"))
        main_(arrayOf(H0,"chain","like","/",h2,S0))
        main_(arrayOf(H0, "chain", "post", "/", "inline", "utf8", "h3"))

        main_(arrayOf(H0, "chain", "send", "/", "localhost:8331"))

        // this all to test an internal assertion
    }

    @Test
    fun m12_state () {
        a_reset()

        main(arrayOf("host", "create", "/tmp/freechains/tests/M120/"))
        thread { main(arrayOf("host", "start", "/tmp/freechains/tests/M120/")) }
        Thread.sleep(100)
        main(arrayOf(H0, "chain", "join", "/"))
        main(arrayOf(H0, "host", "now", "0"))

        main(arrayOf("host", "create", "/tmp/freechains/tests/M121/", "8331"))
        thread { main(arrayOf("host", "start", "/tmp/freechains/tests/M121/")) }
        Thread.sleep(100)
        main(arrayOf(H1, "chain", "join", "/"))
        main(arrayOf(H1, "host", "now", "0"))

        main_(arrayOf(H0, "chain", "post", "/", "inline", "utf8", "h1",S0))
        val h21 = main_(arrayOf(H0, "chain", "post", "/", "inline", "utf8", "h21"))
        val h22 = main_(arrayOf(H0, "chain", "post", "/", "inline", "utf8", "h22"))

        // h0 -> h1 -> h21
        //          -> h22

        main_(arrayOf(H0, "chain", "heads", "accepted", "/")).let {
            assert(it.startsWith("1_")) { it }
        }
        main_(arrayOf(H0, "chain", "heads", "pending", "/")).let {
            assert(it.startsWith("1_")) { it }
        }
        main_(arrayOf(H0, "chain", "heads", "rejected", "/")).let {
            assert(it.startsWith("2_")) { it }
        }

        main_(arrayOf(H0,"chain","like","/",h21,S0))

        //          -> l2
        // h0 -> h1 -> h21
        //          -> h22

        main_(arrayOf(H0, "chain", "heads", "pending", "/")).let { str ->
            str.split(' ').let {
                assert(it.size == 2)
                it.forEach {
                    assert(it.startsWith("2_"))
                }
            }
        }

        main_(arrayOf(H0,"chain","like","/",h22,S0))

        //          -> l2  -> l3
        // h0 -> h1 -> h21
        //          -> h22

        main_(arrayOf(H0, "chain", "heads", "accepted", "/")).let {
            assert(it.startsWith("3_"))
        }
        main_(arrayOf(H0, "chain", "heads", "pending", "/")).let {
            it.split(' ').let {
                assert(it.size == 3)
            }
        }

        main_(arrayOf(H0, "chain", "send", "/", "localhost:8331")).let {
            assert(it.contains("5 / 5"))
        }

////////
        // all accepted
        main_(arrayOf(H0, "host", "now", "${3*hour}"))

        main_(arrayOf(H0, "chain", "heads", "rejected", "/"))
        main_(arrayOf(H0, "chain", "heads", "accepted", "/")).let { str ->
            str.split(' ').let {
                assert(it.size == 3)
                it.forEach {
                    assert(it.startsWith("2_") || it.startsWith("3_"))
                }
            }
        }

        // l4 dislikes h22 (doesnt reject h22 because of fronts rule)
        val l4 = main_(arrayOf(H0,"chain","dislike","/",h22,S0))

        //          -> l2  -> l3 -> l4
        // h0 -> h1 -> h21 ------/
        //          -> h22 -----/

        main_(arrayOf(H0, "chain", "heads", "accepted", "/")).let { str ->
            str.split(' ').let {
                assert(it.size == 1)
            }
            assert(str.contains("4_"))
        }
        main_(arrayOf(H0, "chain", "heads", "rejected", "/")).let {
            assert(it.isEmpty())
        }

        //          -> l2  -> l3
        // h0 -> h1 -> h21
        //          -> h22

        main_(arrayOf(H0,"chain","ban","/",l4))
        /*val h43 =*/ main_(arrayOf(H0, "chain", "post", "/", "inline", "utf8", "h43"))

        //          -> l2 --> l3 -\
        // h0 -> h1 -> h21 --------> h43
        //          -> h22 -------/

        main_(arrayOf(H0, "chain", "heads", "rejected", "/")).let {
            assert(it.startsWith("4_"))
        }

////////

        main_(arrayOf(H1, "host", "now", "${1*hour}"))
        main_(arrayOf(H1,"chain","dislike","/",h22,S0))     // one is not enough
        main_(arrayOf(H1,"chain","dislike","/",h22,S0))     // one is not enough
        main_(arrayOf(H1, "host", "now", "${4*hour}"))
        main_(arrayOf(H1, "chain", "send", "/", "localhost:8330"))

        //          -> l2  -> l3 -> l4
        // h0 -> h1 -> h21
        //          -> h22

        // l4 dislikes h22 (reject it)

        main_(arrayOf(H0, "chain", "heads", "accepted", "/")).let { str ->
            str.split(' ').let {
                assert(it.size == 2)
            }
        }
        //println(">>>")
        main_(arrayOf(H0, "chain", "heads", "rejected", "/")).let {
            assert(it.contains("2_"))
        }
    }

    @Test
    fun m13_reps () {
        a_reset()

        main(arrayOf("host", "create", "/tmp/freechains/tests/M13/"))
        thread { main(arrayOf("host", "start", "/tmp/freechains/tests/M13/")) }
        Thread.sleep(100)
        main(arrayOf(H0, "chain", "join", "/"))

        main(arrayOf(H0, "host", "now", "0"))
        main_(arrayOf(H0, "chain", "post", "/", "inline", "utf8", "h1", S0))
        main_(arrayOf(H0, "chain", "post", "/", "inline", "utf8", "h2", S1)).let {
            main_(arrayOf(H0,S0,"chain","like","/",it))
        }

        // h0 <-- h1 <-- l2
        //            \- h2

        main(arrayOf(H0, "host", "now", "${3*hour}"))
        main_(arrayOf(H0, "chain", "reps", "/", PUB1)).let {
            assert(it == "0")
        }

        main(arrayOf(H0, "host", "now", "${25*hour}"))
        main_(arrayOf(H0, "chain", "reps", "/", PUB1)).let {
            assert(it == "2")
        }

        main_(arrayOf(H0, S1, "chain", "post", "/", "inline", "utf8", "h3"))

        // h0 <-- h1 <-- l2
        //            \- h2 <-- h3

        main_(arrayOf(H0, "chain", "reps", "/", PUB1)).let {
            assert(it == "1")
        }

        main(arrayOf(H0, "host", "now", "${50*hour}"))
        main_(arrayOf(H0, "chain", "reps", "/", PUB1)).let {
            assert(it == "3")
        }

        main(arrayOf(H0, "host", "now", "${53*hour}"))
        main_(arrayOf(H0, "chain", "post", "/", "inline", "utf8", "h4", S1))

        // h0 <-- h1 <-- l2
        //            \- h2 <-- h3 <-- h4

        main_(arrayOf(H0, "chain", "reps", "/", PUB1)).let {
            assert(it == "2")
        }

        main(arrayOf(H0, "host", "now", "${78*hour}"))
        main_(arrayOf(H0, "chain", "reps", "/", PUB1)).let {
            assert(it == "4") {it}
        }

        main_(arrayOf(H0, "chain", "post", "/", "inline", "utf8", "h5", S1))

        // h0 <-- h1 <-- l2
        //            \- h2 <-- h3 <-- h4 <-- h5

        main_(arrayOf(H0, "chain", "reps", "/", PUB1)).let {
            assert(it == "3")
        }

        main(arrayOf(H0, "host", "now", "${1000*hour}"))
        main_(arrayOf(H0, "chain", "reps", "/", PUB1)).let {
            assert(it == "5")
        }
    }

    @Test
    fun m14_remove() {
        a_reset()

        main(arrayOf("host", "create", "/tmp/freechains/tests/M140/"))
        thread { main(arrayOf("host", "start", "/tmp/freechains/tests/M140/")) }
        Thread.sleep(100)
        main(arrayOf("chain", "join", "/"))

        main(arrayOf(H0, "host", "now", "0"))

        /*val h1  =*/ main_(arrayOf(H0, S0, "chain", "post", "/", "inline", "utf8", "h0"))
        val h21 = main_(arrayOf(H0, S1, "chain", "post", "/", "inline", "utf8", "h21"))
        /*val h20 =*/ main_(arrayOf(H0, S0, "chain", "post", "/", "inline", "utf8", "h20"))

        // h0 -> h1 --> h21
        //          \-> h20

        // no double spend
        main_(arrayOf(H0, S0, "chain", "post", "/", "inline", "utf8", "h20x")).let {
            assert(it == "must point to author's previous post")
        }

        main(arrayOf(H0, "host", "now", "${3*hour}"))
        main_(arrayOf(H0, S0, "chain", "post", "/", "inline", "utf8", "h30"))

        // h0 -> h1 --> h20 -> h30
        //          \-> h21

        main(arrayOf(H0, "host", "now", "${6*hour}"))
        /*val l40 =*/ main_(arrayOf(H0, S0, "chain", "like", "/", h21))

        // h0 -> h1 --> h21
        //          \-> h20 -> h30 -> l40

        main(arrayOf(H0, "host", "now", "${9*hour}"))

        val h51 = main_(arrayOf(H0, S1, "chain", "post", "/", "inline", "utf8", "h51"))

        // h0 -> h1 --> h21 -----------------> h51
        //          \-> h20 -> h30 -> l40 -/-> l50

        /*val l50 =*/ main_(arrayOf(H0, S0, "chain", "like", "/", h51))

        main(arrayOf(H0, "host", "now", "${12*hour}"))
        /*val h6 =*/ main_(arrayOf(H0, S1, "chain", "post", "/", "inline", "utf8", "h6"))

        // h0 -> h1 --> h21 -----------------> h51 --> h6
        //          \-> h20 -> h30 -> l40 -/-> l50 -/

        main_(arrayOf(H0, "chain", "heads", "rejected", "/")).let {
            assert(it.startsWith("6_"))
        }

        // nothing changes
        /*val l- =*/ main_(arrayOf(H0, S0, "chain", "dislike", "/", h21))

        // h0 -> h1 --> h21 -----------------> h51 -\-> h6
        //          \-> h20 -> h30 -> l40 -/-> l50 -/-> l-

        // h0 -> h1 --> h21
        //          \-> h20 -> h30 -> l40

        main_(arrayOf(H0, "chain", "heads", "accepted", "/")).let {
            assert(!it.contains("2_"))
        }
        main_(arrayOf(H0, "chain", "heads", "rejected", "/")).let {
            assert(it.startsWith("6_"))
        }

        main(arrayOf(H0, "host", "now", "${15*hour}"))

        main_(arrayOf(H0, "chain", "heads", "accepted", "/")).let { str ->
            str.split(' ').let {
                assert(it.size == 1) { it.size }
                assert(it[0].startsWith("6_"))
            }
        }

        // TODO: check rems/ directory
    }

    @Test
    fun m15_rejected() {
        a_reset()

        main(arrayOf("host", "create", "/tmp/freechains/tests/M150/"))
        thread { main(arrayOf("host", "start", "/tmp/freechains/tests/M150/")) }
        main(arrayOf("host", "create", "/tmp/freechains/tests/M151/", "8331"))
        thread { main(arrayOf("host", "start", "/tmp/freechains/tests/M151/")) }
        Thread.sleep(100)
        main(arrayOf(H0, "chain", "join", "/"))
        main(arrayOf(H1, "chain", "join", "/"))

        main(arrayOf(H0, "host", "now", "0"))
        main(arrayOf(H1, "host", "now", "0"))

        main_(arrayOf(H0, S0, "chain", "post", "/", "inline", "utf8", "0@h1"))
        val h2 = main_(arrayOf(H0, S1, "chain", "post", "/", "inline", "utf8", "1@h2"))
        main_(arrayOf(H0, S0, "chain", "like", "/", h2))

        main_(arrayOf(H0, "chain", "send", "/", "localhost:8331"))

        // HOST-0
        // h0 <- 0@h1 <- 0@l2
        //            <- 1@h2

        // HOST-1
        // h0 <- 0@h1 <- 0@l2
        //            <- 1@h2

        main_(arrayOf(H0, S0, "chain", "dislike", "/", h2))

        // HOST-0
        // h0 <- 0@h1 <- 0@l2 <- 0@l3
        //            <- 1@h2 <.../

        main(arrayOf(H0, "host", "now", "${5*hour}"))
        main(arrayOf(H1, "host", "now", "${5*hour}"))

        main_(arrayOf(H0, "chain", "heads", "accepted", "/")).let { str ->
            str.split(' ').let {
                assert(it.size == 1) { it.size }
                it.forEach { v -> assert(v.startsWith("3_")) }
            }
        }
        main_(arrayOf(H0, "chain", "heads", "rejected", "/")).let { str ->
            str.split(' ').let {
                assert(it.size == 1) { it.size }
                assert(it[0] == h2)     // H0 rejected 1@h2
            }
        }

        main(arrayOf(H0, "host", "now", "${25*hour}"))
        main(arrayOf(H1, "host", "now", "${25*hour}"))

        // HOST-0
        // h0 <- 0@h1 <- 0@l2 <- 0@l3
        //            <- 1@h2 <.../
        main_(arrayOf(H0, "chain", "heads", "rejected", "/")).let { str ->
            str.split(' ').let {
                assert(it.size == 1) { it.size }
                assert(it[0] == h2)     // H0 rejected 1@h2
            }
        }

        main_(arrayOf(H1, S1, "chain", "post", "/", "inline", "utf8", "1@h3"))

        // HOST-1
        // h0 <- 0@h1 <- 0@l2 | <-\
        //            <- 1@h2 | <- 1@h3
        main_(arrayOf(H1, "chain", "heads", "accepted", "/")).let { str ->
            str.split(' ').let {
                assert(it.size == 2) { it.size }
                it.forEach { v -> assert(v.startsWith("2_")) }
            }
        }
        main_(arrayOf(H1, "chain", "heads", "pending", "/")).let { str ->
            str.split(' ').let {
                assert(it.size == 1) { it.size }
                it.forEach { v -> assert(v.startsWith("3_")) }
            }
        }

        // send H1 -> H0
        // 1@h2 is rejected in H0, so 1@h3 will be rejected
        main_(arrayOf(H1, "chain", "send", "/", "localhost:8330")).let {
            assert(it.contains("0 / 1"))
        }

        // try again after like
        main_(arrayOf(H0, S0, "chain", "like", "/", h2))

        // HOST-0
        // h0 <- 0@h1 <- 0@l2 <- 0@l3 <- 0@l4
        //            <- 1@h2 <.../......./

        main_(arrayOf(H1, "chain", "send", "/", "localhost:8330")).let {
            assert(it.contains("1 / 1"))
        }

        // HOST-1
        // h0 <- 0@h1 <- 0@l2 | <-\ (...l3/l4)
        //            <- 1@h2 | <- 1@h3

        main_(arrayOf(H0, "chain", "heads", "rejected", "/")).let {
            assert(it.isEmpty())
        }
        main_(arrayOf(H1, "chain", "heads", "rejected", "/")).let {
            assert(it.isEmpty())
        }
    }
}
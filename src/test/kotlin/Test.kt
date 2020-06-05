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
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer.Alphanumeric
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Socket
import java.time.Instant
import java.util.*
import kotlin.concurrent.thread

/*
 *  TODO:
 *                                reps             28-02    29-02    17-03   19-03    25-04   28-04   04-05
 *  -   736 ->   809 ->   930 ->  1180 ->  1131 ->  1365 ->  1434 ->  1598 -> 1681 -> 1500 -> 1513 -> 1555 LOC
 *  - 10553 -> 10555 -> 10557 -> 10568 -> 10575 -> 10590 -> 10607 ->  5691 -> .... -> 5702 KB
 *  - ping return version
 *  - peer chains return one by line with parameters
 *  - return codes from main_
 *  - permission for client
 *  - android app client para controlar host: registrar hosts, chains, users, etc, receber notificacoes
 *  - passar dir de instalacao p/ install
 *  - show IPs in connections
 *  - test 50 random very slow chains each node
 *  - two simulation at the same time
 *  - allow binary pay
 *  - site to track: last access, chains heads
 *  - liferea, /home, docs
 *  - PROTO:
 *    - autor fork: merge largest instead of reject
 *    - prunning (hash of bases, starts with genesis), if they don't match, permanent fork
 *      - new idea 4-month window every day at 0:00
 *  - HOST: "create" receives pub/pvt args
 *    - creates pvt chain oo (for logs, periodic bcast)
 *    - save CFG in a chain
 *    - join reputation system (evaluate continue@xxx)
 *    - replicate command (all state)
 *    - all conns start with pubs from both ends
 *  - TEST
 *    - oonly, N16_blocked, S128_payload
 *    - proof that pay="" is really hidden
 *  - REFACTOR
 *    - join (stop/now), they use connection
 *    - chain/.* move dir files
 *  - CMDS
 *    - freechains now s/ time (retorna now)
 *    - freechains host restart
 *    - freechains crypto w/o passphrase (to self generate)
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
 *    - when too many blocks, no reader returns soon, so reader timeout
 *  - IDEAS:
 *    - chain for restauration of state in other host holding all necessary commands
 *  - Aether client
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

val H   = Immut(0, Payload(false,""),null, null, emptyArray())
val HC  = H.copy(pay=H.pay.copy(true))

const val PVT0 = "6F99999751DE615705B9B1A987D8422D75D16F5D55AF43520765FA8C5329F7053CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322"
const val PUB0 = "3CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322"
const val PVT1 = "6A416117B8F7627A3910C34F8B35921B15CF1AC386E9BB20E4B94AF0EDBE24F4E14E4D7E152272D740C3CA4298D19733768DF7E74551A9472AAE384E8AB34369"
const val PUB1 = "E14E4D7E152272D740C3CA4298D19733768DF7E74551A9472AAE384E8AB34369"
const val SHA0 = "64976DF4946F45D6EF37A35D06A1D9A1099768FBBC2B4F95484BA390811C63A2"

const val H0 = "--host=localhost:$PORT_8330"
const val H1 = "--host=localhost:8331"
const val S0 = "--sign=$PVT0"
const val S1 = "--sign=$PVT1"

fun main__ (args: Array<String>) : String {
    return main_(args).let { (ok,msg) ->
        assert(ok)
        if (msg == null) "" else msg
    }
}

@TestMethodOrder(Alphanumeric::class)
class Tests {

    companion object {
        @BeforeAll
        @JvmStatic
        internal fun reset () {
            assert(File("/tmp/freechains/tests/").deleteRecursively())
        }
    }

    @BeforeEach
    fun stop () {
        main_(arrayOf("host", "stop"))
        main_(arrayOf(H1,"host", "stop"))
    }

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
        val h = Host_load("/tmp/freechains/tests/local/")
        val c1 = h.chainsJoin("#uerj")

        val c2 = h.chainsLoad(c1.name)
        assertThat(c1.hashCode()).isEqualTo(c2.hashCode())

        val blk = c2.blockNew(HC, "", null, null)
        val blk2 = c2.fsLoadBlock(blk.hash)
        assertThat(blk.hashCode()).isEqualTo(blk2.hashCode())

        assert(c2.bfsFrontsIsFromTo(blk.hash,blk.hash))
    }

    @Test
    fun c1_post() {
        val host = Host_load("/tmp/freechains/tests/local/")
        val chain = host.chainsJoin("@$PUB0")
        val n1 = chain.blockNew(H, "", PVT0, null)
        val n2 = chain.blockNew(H, "", PVT0, null)
        val n3 = chain.blockNew(H, "", null, null)

        var ok = false
        try {
            val n = n3.copy(immut=n3.immut.copy(pay=n3.immut.pay.copy(hash="xxx")))
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
    fun d3_proto() {
        // SOURCE
        val src = Host_load("/tmp/freechains/tests/src/")
        val srcChain = src.chainsJoin("@$PUB1")
        srcChain.blockNew(HC, "", PVT1, null)
        srcChain.blockNew(HC, "", PVT1, null)
        thread { Daemon(src).daemon() }

        // DESTINY
        val dst = Host_load("/tmp/freechains/tests/dst/", 8331)
        dst.chainsJoin("@$PUB1")
        thread { Daemon(dst).daemon() }
        Thread.sleep(200)

        main__(arrayOf("peer", "localhost:8331", "ping")).let {
            println(">>> $it")
            assert(it.toInt() < 50)
        }
        main_(arrayOf("peer", "localhost:11111", "ping")).let {
            assert(!it.first && it.second==null)
        }
        main__(arrayOf("peer", "localhost:8331", "chains")).let {
            assert(it == "@$PUB1")
        }

        main_(arrayOf("peer", "localhost:8331", "send", "@$PUB1"))
        Thread.sleep(200)

        main_(arrayOf(H1, "host", "stop"))
        main_(arrayOf("host", "stop"))
        Thread.sleep(200)

        // TODO: check if dst == src
        // $ diff -r /tmp/freechains/tests/dst/ /tmp/freechains/tests/src/
    }

    @Test
    fun f1_peers() {
        val h1 = Host_load("/tmp/freechains/tests/h1/", PORT_8330)
        val h1Chain = h1.chainsJoin("@$PUB1")
        h1Chain.blockNew(H, "", PVT1, null)
        h1Chain.blockNew(H, "", PVT1, null)

        val h2 = Host_load("/tmp/freechains/tests/h2/", 8331)
        val h2Chain = h2.chainsJoin("@$PUB1")
        h2Chain.blockNew(H, "", PVT1, null)
        h2Chain.blockNew(H, "", PVT1, null)

        Thread.sleep(200)
        thread { Daemon(h1).daemon() }
        thread { Daemon(h2).daemon() }
        Thread.sleep(200)
        main_(arrayOf(H0, "peer", "localhost:8331", "recv", "@$PUB1"))
        Thread.sleep(200)
        main_(arrayOf(H1, "host", "stop"))
        main_(arrayOf("host", "stop"))
        Thread.sleep(200)

        // TODO: check if 8332 (h2) < 8331 (h1)
        // $ diff -r /tmp/freechains/tests/h1 /tmp/freechains/tests/h2/
    }

    @Test
    fun m00_chains() {
        thread {
            main_(arrayOf("host", "start", "/tmp/freechains/tests/M0/"))
        }
        Thread.sleep(200)
        main__(arrayOf("chains", "list")).let {
            assert(it == "")
        }
        main__(arrayOf("chains", "leave", "#xxx")).let {
            assert(it == "false")
        }
        main__(arrayOf("chains", "join", "#xxx")).let {
            assert(it.isNotEmpty())
        }
        main__(arrayOf("chains", "join", "#yyy")).let {
            assert(it.isNotEmpty())
        }
        main__(arrayOf("chains", "list")).let {
            assert(it == "#yyy #xxx")
        }
        main__(arrayOf("chains", "leave", "#xxx")).let {
            assert(it == "true")
        }
    }

    @Test
    fun m01_args() {
        //a_reset()
        thread {
            main_(arrayOf("host", "start", "/tmp/freechains/tests/M1/"))
        }
        Thread.sleep(200)
        main_(arrayOf("chains", "join", "#xxx"))

        assert(main__(arrayOf("chain", "#xxx", "genesis")).startsWith("0_"))
        assert(main__(arrayOf("chain", "#xxx", "heads", "linked")).startsWith("0_"))

        main_(arrayOf("chain", "#xxx", "post", "inline", "aaa", S0))
        assert(main__(arrayOf("chain", "#xxx", "heads", "linked")).startsWith("1_"))
        main__(arrayOf("chain", "#xxx", "heads", "blocked")).let {
            assert(it.isEmpty())
        }
        main__(arrayOf("chain", "#xxx", "heads", "linked")).let { list ->
            list.split(' ').toTypedArray().let {
                assert(it.size == 1)
                assert(it[0].startsWith("1_"))
            }
        }

        main_(arrayOf("chain", "#xxx", "get", "block", "0_B5E21297B8EBEE0CFA0FA5AD30F21B8AE9AE9BBF25F2729989FE5A092B86B129")).let {
            println(it)
            assert(!it.first && it.second.equals("! block not found"))
        }

        /*val h2 =*/ main_(arrayOf(S0, "chain", "#xxx", "post", "file", "/tmp/freechains/tests/M1/chains/#xxx/chain"))

        //main_(arrayOf(S0, "chain", "/xxx", "post", "file", "/tmp/20200504192434-0.eml"))

        // h0 -> h1 -> h2

        assert(main__(arrayOf("chain", "#xxx", "heads", "linked")).startsWith("2_"))
        assert(main__(arrayOf("chain", "#xxx", "heads", "blocked")).isEmpty())

        //main_(arrayOf("chain", "#xxx", "post", "file", "base64", "/bin/cat"))
        main_(arrayOf("host", "stop"))
        // TODO: check genesis 2x, "aaa", "host"
        // $ cat /tmp/freechains/tests/M1/chains/xxx/blocks/*
    }

    @Test
    fun m01_trav() {
        thread {
            main_(arrayOf("host", "start", "/tmp/freechains/tests/trav/"))
        }
        Thread.sleep(200)
        main_(arrayOf("chains", "join", "#"))
        val gen = main__(arrayOf("chain", "#", "genesis"))
        main_(arrayOf("chain", "#", "post", "inline", "aaa", S0))
        main__(arrayOf("chain", "#", "traverse", "all", gen)).let {
            it.split(" ").let {
                assert(it.size == 1 && it[0].startsWith("1_"))
            }
        }
    }

    @Test
    fun m01_listen() {
        thread {
            main_(arrayOf("host", "start", "/tmp/freechains/tests/listen/"))
        }
        Thread.sleep(200)
        main_(arrayOf("chains", "join", "#"))

        var ok = 0
        val t1 = thread {
            val socket = Socket("localhost", PORT_8330)
            val writer = DataOutputStream(socket.getOutputStream()!!)
            val reader = DataInputStream(socket.getInputStream()!!)
            writer.writeLineX("$PRE chain # listen")
            val n = reader.readLineX().toInt()
            assert(n == 1) { "error 1"}
            ok++
        }

        val t2 = thread {
            val socket = Socket("localhost", PORT_8330)
            val writer = DataOutputStream(socket.getOutputStream()!!)
            val reader = DataInputStream(socket.getInputStream()!!)
            writer.writeLineX("$PRE chains listen")
            val x = reader.readLineX()
            assert(x == "1 #") { "error 2"}
            ok++
        }

        Thread.sleep(200)
        main_(arrayOf("chain", "#", "post", "inline", "aaa", S0))
        t1.join()
        t2.join()
        assert(ok == 2)
    }
    @Test
    fun m02_crypto() {
        //a_reset()
        thread {
            main_(arrayOf("host", "start", "/tmp/freechains/tests/M2/"))
        }
        Thread.sleep(200)
        val lazySodium = LazySodiumJava(SodiumJava())
        val kp: KeyPair = lazySodium.cryptoSignKeypair()
        val pk: Key = kp.publicKey
        val sk: Key = kp.secretKey
        assert(lazySodium.cryptoSignKeypair(pk.asBytes, sk.asBytes))
        //println("TSTTST: ${pk.asHexString} // ${sk.asHexString}")
        main_(arrayOf("crypto", "create", "shared", "senha secreta"))
        main_(arrayOf("crypto", "create", "pubpvt", "senha secreta"))

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
        val host = Host_load("/tmp/freechains/tests/M2/")
        val c1 = host.chainsJoin("#sym")
        c1.blockNew(HC, "", null, null)
        val c2 = host.chainsJoin("@$PUB0")
        c2.blockNew(H, "", PVT0, PVT0)
    }

    @Test
    fun m04_crypto_encrypt() {
        val host = Host_load("/tmp/freechains/tests/M2/")
        val c1 = host.chainsLoad("#sym")
        //println(c1.root)
        val n1 = c1.blockNew(HC, "aaa", null, SHA0)
        //println(n1.hash)
        val n2 = c1.fsLoadPay(n1.hash, SHA0)
        assert(n2 == "aaa")
        //Thread.sleep(500)
    }

    @Test
    fun m05_crypto_encrypt_sym() {
        //a_reset()
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M50/")) }
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M51/", "8331")) }
        Thread.sleep(200)
        main_(
            arrayOf (
                "chains",
                "join",
                "#xxx"
            )
        )
        main_(
            arrayOf (
                H1,
                "chains",
                "join",
                "#xxx"
            )
        )

        main_(arrayOf("chain", "#xxx", "post", "inline", "aaa", "--crypt=$SHA0"))
        main_(arrayOf("peer", "localhost:8331", "send", "#xxx"))
    }

    @Test
    fun m06_crypto_encrypt_asy() {
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M60/")) }
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M61/", "8331")) }
        Thread.sleep(200)
        main_(arrayOf("chains", "join", "@$PUB0"))
        main_(arrayOf(H1, "chains", "join", "@$PUB0"))
        val hash = main__(arrayOf("chain", "@$PUB0", "post", "inline", "aaa", S0, "--crypt=$PVT0"))

        val pay = main__(arrayOf("chain", "@$PUB0", "get", "payload", hash, "--crypt=$PVT0"))
        assert(pay == "aaa")

        main_(arrayOf("peer", "localhost:8331", "send", "@$PUB0"))
        val json2 = main__(arrayOf(H1, "chain", "@$PUB0", "get", "block", hash))
        val blk2 = json2.jsonToBlock()
        assert(blk2.immut.pay.crypt)

        val h2 = main__(arrayOf("chain", "@$PUB0", "post", "inline", "bbbb", S1))
        val pay2 = main__(arrayOf("chain", "@$PUB0", "get", "payload", h2))
        assert(pay2 == "bbbb")
    }

    @Test
    fun m06x_crypto_encrypt_asy() {
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M60x/")) }
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M61x/", "8331")) }
        Thread.sleep(200)
        main_(arrayOf("chains", "join", "@!$PUB0"))
        main_(arrayOf(H1, "chains", "join", "@!$PUB0"))
        val hash = main__(arrayOf("chain", "@!$PUB0", "post", "inline", "aaa", S0, "--crypt=$PVT0"))

        val pay = main__(arrayOf("chain", "@!$PUB0", "get", "payload", hash, "--crypt=$PVT0"))
        assert(pay == "aaa")

        main_(arrayOf("peer", "localhost:8331", "send", "@!$PUB0"))
        val json2 = main__(arrayOf(H1, "chain", "@!$PUB0", "get", "block", hash))
        val blk2 = json2.jsonToBlock()
        assert(blk2.immut.pay.crypt)

        main_(arrayOf("chain", "@!$PUB0", "post", "inline", "bbbb", S1)).let {
            assert(!it.first && it.second.equals("! must be from owner"))
        }
    }

    @Test
    fun m07_genesis_fork() {
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M70/")) }
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M71/", "8331")) }
        Thread.sleep(200)

        main_(arrayOf(H0, "chains", "join", "#"))
        main_(arrayOf(H0, "chain", "#", "post", "inline", "first-0", S0))
        main_(arrayOf(H1, "chains", "join", "#"))
        main_(arrayOf(H1, "chain", "#", "post", "inline", "first-1", S1))
        Thread.sleep(200)

        // H0: g <- f0
        // H1: g <- f1

        val r0 = main__(arrayOf(H1, "peer", "localhost:$PORT_8330", "recv", "#"))
        val r1 = main__(arrayOf(H1, "peer", "localhost:$PORT_8330", "send", "#"))
        assert(r0 == r1 && r0 == "0 / 1")

        val r00 = main__(arrayOf(H0, "chain", "#", "reps", PUB0))
        val r11 = main__(arrayOf(H1, "chain", "#", "reps", PUB1))
        val r10 = main__(arrayOf(H0, "chain", "#", "reps", PUB1))
        val r01 = main__(arrayOf(H1, "chain", "#", "reps", PUB0))
        assert(r00.toInt() == 29)
        assert(r11.toInt() == 29)
        assert(r10.toInt() == 0)
        assert(r01.toInt() == 0)

        main_(arrayOf(H0, "host", "now", (getNow() + 1*day).toString()))
        main_(arrayOf(H1, "host", "now", (getNow() + 1*day).toString()))

        val x0 = main__(arrayOf(H0, "chain", "#", "reps", PUB0))
        val x1 = main__(arrayOf(H1, "chain", "#", "reps", PUB1))
        assert(x0.toInt() == 30)
        assert(x1.toInt() == 30)
    }

    @Test
    fun m08_likes() {
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M80/")) }
        Thread.sleep(200)
        main_(arrayOf("chains", "join", "@$PUB0"))

        main_(arrayOf(H0, "host", "now", "0"))

        val h11 = main__(arrayOf("chain", "@$PUB0", "post", "inline", "h11", S0))
        val h22 = main__(arrayOf("chain", "@$PUB0", "post", "inline", "h22", S1))
        /*val h21 =*/ main_(arrayOf("chain", "@$PUB0", "post", "inline", "h21", S0))

        // h0 -> h11 -> h21
        //          \-> h22

        main__(arrayOf("chain", "@$PUB0", "heads", "linked")).let { str ->
            str.split(' ').let {
                assert(it.size == 1)
            }
            assert(str.startsWith("2_"))
        }

        main_(arrayOf("chain", "@$PUB0", "like", h22, S0, "--why=l3")) // l3

        // h0 -> h11 -> h21 -> l3
        //          \-> h22 /

        main__(arrayOf("chain", "@$PUB0", "heads", "linked")).let { str ->
            str.split(' ').let {
                assert(it.size == 1)
                assert(it[0].startsWith("3_") || it[1].startsWith("3_"))
            }
        }
        assert( "0" == main__(arrayOf("chain", "@$PUB0", "reps", PUB1)))

        main_(arrayOf(H0, "host", "now", (3*hour).toString()))
        /*val h41 =*/ main_(arrayOf("chain", "@$PUB0", "post", "inline", "41", S0))

        // h0 -> h11 -> h21 -> l3 -> h41
        //          \-> h22 --/

        main_(arrayOf(H0, "host", "now", (1*day+4*hour).toString()))

        assert(main__(arrayOf("chain", "@$PUB0", "heads", "linked")).startsWith("4_"))
        assert("29" == main__(arrayOf("chain", "@$PUB0", "reps", PUB0)))
        assert( "2" == main__(arrayOf("chain", "@$PUB0", "reps", PUB1)))

        // like myself
        main__(arrayOf("chain", "@$PUB0", "like", h11, S0)).let {
            assert(it == "like must not target itself")
        }

        val l5 = main__(arrayOf("chain", "@$PUB0", "like", h22, S0, "--why=l5")) // l5

        // h0 -> h11 -> h21 -> l3 -> h41 -> l5
        //          \-> h22 --/

        main__(arrayOf("chain", "@$PUB0", "reps", PUB0)).let {
            assert(it == "28")
        }
        main__(arrayOf("chain", "@$PUB0", "reps", PUB1)).let {
            assert(it == "3")
        }

        main__(arrayOf(H0, "chain", "@$PUB0", "heads", "linked")).let { str ->
            str.split(' ').let {
                assert(it.size == 1)
            }
            assert(str.contains("5_"))
        }

        // height is ~also 5~ 6
        /*val l6 =*/ main_(arrayOf("chain","@$PUB0","dislike",l5,"--why=l6",S1))

        // h0 <- h11 <- h21 <- l3 <- h41 <- l5
        //          \         /               \
        //           \- h22 <-------           l6

        main__(arrayOf("chain", "@$PUB0", "reps", PUB1)).let {
            assert(it == "2")
        }
        main__(arrayOf("chain", "@$PUB0", "reps", PUB0)).let {
            assert(it == "27")
        }

        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M81/", "8331")) }
        Thread.sleep(200)
        main_(arrayOf(H1, "chains", "join", "@$PUB0"))

        // I'm in the future, old posts will be refused
        main_(arrayOf(H1, "host", "now", Instant.now().toEpochMilli().toString()))

        val n1 = main__(arrayOf(H0, "peer", "localhost:8331", "send", "@$PUB0"))
        assert(n1 == "0 / 7")

        main__(arrayOf(H0, "chain", "@$PUB0", "heads", "linked")).let { str ->
            str.split(' ').let {
                assert(it.size == 1)
                it.forEach { assert(it.contains("6_")) }
            }
        }

        // only very old (H1/H2/L3)
        main_(arrayOf(H1, "host", "now", "0"))
        val n2 = main__(arrayOf(H1, "peer", "localhost:$PORT_8330", "recv", "@$PUB0"))
        assert(n2 == "4 / 7") { n2 }
        main__(arrayOf(H1, "chain", "@$PUB0", "heads", "linked")).let {
            assert(it.startsWith("3_"))
        }

        // h0 <- h11 <- h21 <- l3
        //          \
        //           \- h22

        // still the same
        main_(arrayOf(H1, "host", "now", "${2*hour}"))
        main__(arrayOf(H0, "peer", "localhost:8331", "send", "@$PUB0")).let {
            assert(it == "0 / 3")
        }
        assert("0" == main__(arrayOf("chain", "@$PUB0", "reps", PUB1)))

        // now ok
        main_(arrayOf(H1, "host", "now", "${1*day + 4*hour + 100}"))
        main__(arrayOf(H0, "peer", "localhost:8331", "send", "@$PUB0")).let {
            assert(it == "3 / 3")
        }
        main__(arrayOf(H1, "chain", "@$PUB0", "heads", "linked")).let {
            assert(it.startsWith("6_"))
        }

        // h0 <- h11 <- h21 <- l3 <- h41 <- l5
        //          \               /         \
        //           \- h22 <-------           l6

        assert("2" == main__(arrayOf("chain", "@$PUB0", "reps", PUB1)))
        val h7 = main__(arrayOf(H1, "chain", "@$PUB0", "post", "inline", "no rep", S1))

        // h0 <- h11 <- h21 <- l3 <- h41 <- l5
        //          \               /         \
        //           \- h22 <-------           l6 <- h7

        main__(arrayOf(H0, "peer", "localhost:8331", "recv", "@$PUB0")).let {
            assert(it == "1 / 1")
        }
        main__(arrayOf(H1, "chain", "@$PUB0", "heads", "linked")).let {
            assert(it.startsWith("7_"))
        }

        main_(arrayOf(H1,"chain","@$PUB0","like",h7,S0))

        // h0 <- h11 <- h21 <- l3 <- h41 <- l5          l7
        //          \               /         \        /
        //           \- h22 <-------           l6 <- h7

        main__(arrayOf(H1, "chain", "@$PUB0", "heads", "linked")).let { str ->
            str.split(' ').let {
                assert(it.size == 1)
                it.forEach {
                    assert(it.startsWith("8_"))
                }
            }
        }

        main__(arrayOf(H1, "peer", "localhost:$PORT_8330", "send", "@$PUB0")).let {
            assert(it == "1 / 1")
        }

        // flush after 2h
        main_(arrayOf(H0, "host", "now", "${1*day + 7*hour}"))
        main_(arrayOf(H1, "host", "now", "${1*day + 7*hour}"))

        main__(arrayOf(H0, "chain", "@$PUB0", "heads", "linked")).let { str ->
            str.split(' ').let {
                assert(it.size == 1)
                it.forEach {
                    assert(it.startsWith("8_"))
                }
            }
        }
        main__(arrayOf(H1, "chain", "@$PUB0", "heads", "linked")).let { str ->
            str.split(' ').let {
                assert(it.size == 1)
                it.forEach {
                    assert(it.startsWith("8_"))
                }
            }
        }

        // new post, no rep
        val h8 = main__(arrayOf(H1, "chain", "@$PUB0", "post", "inline", "no sig"))

        // h0 <- h11 <- h21 <- l3 <- h41 <- l5          l7 <- h8
        //          \               /         \        /
        //           \- h22 <-------           l6 <- h7

        main__(arrayOf(H1, "peer", "localhost:$PORT_8330", "send", "@$PUB0")).let {
            assert(it.equals("1 / 1"))
        }

        main__(arrayOf(H1, "chain", "@$PUB0", "heads", "blocked")).let {
            assert(it.startsWith("9_"))
        }

        main__(arrayOf(H1, "chain", "@$PUB0", "get", "payload", h8))
            .let {
                assert(it == "no sig")
            }

        // like post w/o pub
        main_(arrayOf(H1,"chain","@$PUB0","like",h8,S0))

        // h0 <- h11 <- h21 <- l3 <- h41 <- l5       l7       l9
        //          \               /         \    /         /
        //           \- h22 <-------           l6 <- h7 <- h8

        main__(arrayOf(H1, "peer", "localhost:$PORT_8330", "send", "@$PUB0")).let {
            assert (it == "1 / 1")
        }
        main__(arrayOf(H1, "chain", "@$PUB0", "reps", PUB0)).let {
            assert(it == "25")
        }

        main_(arrayOf(H1, "host", "now", "${1*day + 10*hour}"))

        main__(arrayOf(H1, "chain", "@$PUB0", "reps", PUB0)).let {
            assert(it == "25")
        }
        main__(arrayOf(H0, "chain", "@$PUB0", "reps", PUB0)).let {
            assert(it == "25")
        }

        val ln = main__(arrayOf(H0, "chain", "@$PUB0", "reps", h7))
        val l1 = main__(arrayOf(H0, "chain", "@$PUB0", "reps", h11))
        val l2 = main__(arrayOf(H0, "chain", "@$PUB0", "reps", h22))
        val l3 = main__(arrayOf(H0, "chain", "@$PUB0", "reps", l5))
        val l4 = main__(arrayOf(H0, "chain", "@$PUB0", "reps", h8))
        println("$ln // $l1 // $l2 // $l3 // $l4")
        assert(ln == "1" && l1 == "0" && l2 == "2" && l3 == "-1" && l4 == "1")
    }

    @Test
    fun m10_cons() {
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M100/")) }
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M101/", "8331")) }
        Thread.sleep(200)
        main_(arrayOf(H0, "chains", "join", "@$PUB0"))
        main_(arrayOf(H1, "chains", "join", "@$PUB0"))
        main_(arrayOf(H0, "host", "now", "0"))
        main_(arrayOf(H1, "host", "now", "0"))

        val h1 = main__(arrayOf(H0, "chain", "@$PUB0", "post", "inline", "h1", S1))
        val h2 = main__(arrayOf(H0, "chain", "@$PUB0", "post", "inline", "h2", S1))
        val hx = main__(arrayOf(H0, "chain", "@$PUB0", "post", "inline", "hx", S0))

        // h1 <- h2 (a) <- hx (r)

        val ps1 = main__(arrayOf(H0, "chain", "@$PUB0", "heads", "linked"))
        val rs1 = main__(arrayOf(H0, "chain", "@$PUB0", "heads", "blocked"))
        assert(!ps1.contains(h1) && !ps1.contains(h2) &&  ps1.contains(hx))
        assert(!rs1.contains(h1) && !rs1.contains(h2) && !rs1.contains(hx))

        main_(arrayOf(H1, "peer", "localhost:$PORT_8330", "recv", "@$PUB0"))

        main__(arrayOf(H1, "chain", "@$PUB0", "post", "inline", "h3",S1)).let {
            //assert(it == "backs must be accepted")
        }

        // h1 <- h2 (p) <- h3
        //   \-- hx (a)

        main_(arrayOf(H1, "host", "now", "${3*hour}"))

        val h4 = main__(arrayOf(H1, "chain", "@$PUB0", "post", "inline", "h4",S1))
        assert(h4.startsWith("5_"))

        // h1 <- h2 (a) <- h3 <- h4
        //   \-- hx (a)

        main_(arrayOf(H0, "peer", "localhost:8331", "send", "@$PUB0"))
        main_(arrayOf(H1, "host", "now", "${6*hour}"))

        main__(arrayOf(H1, "chain", "@$PUB0", "post", "inline", "h5")).let {
            assert(it.startsWith("6_"))
        }
    }

    @Test
    fun m11_send_after_tine() {
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M100/")) }
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M101/", "8331")) }
        Thread.sleep(200)
        main_(arrayOf(H0, "chains", "join", "#"))
        main_(arrayOf(H1, "chains", "join", "#"))

        main_(arrayOf(H0, "chain", "#", "post", "inline", "h1",S0))
        val h2 = main__(arrayOf(H0, "chain", "#", "post", "inline", "h2"))
        main_(arrayOf(H0,"chain","#","like",h2,S0))
        main_(arrayOf(H0, "chain", "#", "post", "inline", "h3"))

        main_(arrayOf(H0, "peer", "localhost:8331", "send", "#"))

        // this all to test an internal assertion
    }

    @Test
    fun m12_state () {
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M120/")) }
        Thread.sleep(200)
        main_(arrayOf(H0, "chains", "join", "#"))
        main_(arrayOf(H0, "host", "now", "0"))

        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M121/", "8331")) }
        Thread.sleep(200)
        main_(arrayOf(H1, "chains", "join", "#"))
        main_(arrayOf(H1, "host", "now", "0"))

        main_(arrayOf(H0, "chain", "#", "post", "inline", "h1",S0))
        val h21 = main__(arrayOf(H0, "chain", "#", "post", "inline", "h21"))
        val h22 = main__(arrayOf(H0, "chain", "#", "post", "inline", "h22"))

        // h0 -> h1 -> h21
        //          -> h22

        main__(arrayOf(H0, "chain", "#", "heads", "linked")).let {
            assert(it.startsWith("1_")) { it }
        }
        main__(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert(it.startsWith("2_")) { it }
        }

        main_(arrayOf(H0,"chain","#","like",h21,S0))

        // h0 -> h1 -> h21 -> l2
        //          -> h22

        main__(arrayOf(H0, "chain", "#", "heads", "linked")).let { str ->
            str.split(' ').let {
                assert(it.size == 1)
                it.forEach {
                    assert(it.startsWith("3_"))
                }
            }
        }

        main_(arrayOf(H0,"chain","#","like",h22,S0))

        // h0 -> h1 -> h21 -> l2 -> l3
        //          -> h22

        main__(arrayOf(H0, "chain", "#", "heads", "linked")).let {
            it.split(' ').let {
                assert(it.size == 1)
            }
        }
        assert(main__(arrayOf("chain", "#", "heads", "blocked")).isEmpty())

        main__(arrayOf(H0, "peer", "localhost:8331", "send", "#")).let {
            assert(it.contains("5 / 5"))
        }

////////
        // all accepted
        main_(arrayOf(H1, "host", "now", "${3*hour}"))

        assert(main__(arrayOf(H1, "chain", "#", "heads", "blocked")).isEmpty())
        main__(arrayOf(H1, "chain", "#", "heads", "linked")).let { str ->
            assert(str.startsWith("4_"))
            str.split(' ').let {
                assert(it.size == 1)
            }
        }

        // l4 dislikes h22
        /*val l4 =*/ main_(arrayOf(H0,"chain","#","dislike",h22,S0))

        // h0 -> h1 -> h21 -> l2 -> l3 -> l4
        //          -> h22

        main__(arrayOf(H0, "chain", "#", "heads", "linked")).let { str ->
            str.split(' ').let {
                assert(it.size == 1)
            }
            assert(str.contains("5_"))
        }
        main__(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert(it.isEmpty())
        }

        /*val h43 =*/ main_(arrayOf(H0, "chain", "#", "post", "inline", "h43"))

        // h0 -> h1 -> h21 -> l2 -> l3 -> l4 -> h43
        //          -> h22

        main__(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert(it.startsWith("6_"))
        }

////////

        main_(arrayOf(H1, "host", "now", "${1*hour}"))
        main_(arrayOf(H1,"chain","#","dislike",h22,S0))     // one is not enough
        main_(arrayOf(H1,"chain","#","dislike",h22,S0))     // one is not enough
        main_(arrayOf(H1, "host", "now", "${4*hour}"))
        main_(arrayOf(H1, "peer", "localhost:$PORT_8330", "send", "#"))  // errors when merging

        // l4 dislikes h22 (reject it)
        // TODO: check if h22 contents are empty

        // h0 -> h1 -> h21 -> l2 -> l3 -> lx -> ly
        //          -> h22

        main__(arrayOf(H1, "chain", "#", "heads", "blocked")).let {
            assert(it.isEmpty())
        }
        main__(arrayOf(H1, "chain", "#", "heads", "linked")).let {
            assert(it.startsWith("6_"))
        }
    }

    @Test
    fun m13_reps () {
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M13/")) }
        Thread.sleep(200)
        main_(arrayOf(H0, "chains", "join", "#"))

        main_(arrayOf(H0, "host", "now", "0"))
        main_(arrayOf(H0, "chain", "#", "post", "inline", "h1", S0))
        main__(arrayOf(H0, "chain", "#", "post", "inline", "h2", S1)).let {
            main_(arrayOf(H0,S0,"chain","#","like",it))
        }

        // h0 <-- h1 <-- l2
        //            \- h2

        main_(arrayOf(H0, "host", "now", "${3*hour}"))
        main__(arrayOf(H0, "chain", "#", "reps", PUB1)).let {
            assert(it == "0")
        }

        main_(arrayOf(H0, "host", "now", "${25*hour}"))
        main__(arrayOf(H0, "chain", "#", "reps", PUB1)).let {
            assert(it == "2")
        }

        main_(arrayOf(H0, S1, "chain", "#", "post", "inline", "h3"))

        // h0 <-- h1 <-- l2
        //            \- h2 <-- h3

        main__(arrayOf(H0, "chain", "#", "reps", PUB1)).let {
            assert(it == "1")
        }

        main_(arrayOf(H0, "host", "now", "${50*hour}"))
        main__(arrayOf(H0, "chain", "#", "reps", PUB1)).let {
            assert(it == "3")
        }

        main_(arrayOf(H0, "host", "now", "${53*hour}"))
        main_(arrayOf(H0, "chain", "#", "post", "inline", "h4", S1))

        // h0 <-- h1 <-- l2
        //            \- h2 <-- h3 <-- h4

        main__(arrayOf(H0, "chain", "#", "reps", PUB1)).let {
            assert(it == "2")
        }

        main_(arrayOf(H0, "host", "now", "${78*hour}"))
        main__(arrayOf(H0, "chain", "#", "reps", PUB1)).let {
            assert(it == "4") {it}
        }

        main_(arrayOf(H0, "chain", "#", "post", "inline", "h5", S1))

        // h0 <-- h1 <-- l2
        //            \- h2 <-- h3 <-- h4 <-- h5

        main__(arrayOf(H0, "chain", "#", "reps", PUB1)).let {
            assert(it == "3")
        }

        main_(arrayOf(H0, "host", "now", "${1000*hour}"))
        main__(arrayOf(H0, "chain", "#", "reps", PUB1)).let {
            assert(it == "5")
        }
    }

    @Test
    fun m14_remove() {
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M140/")) }
        Thread.sleep(200)
        main_(arrayOf("chains", "join", "#"))

        main_(arrayOf(H0, "host", "now", "0"))

        /*val h1  =*/ main_(arrayOf(H0, S0, "chain", "#", "post", "inline", "h0"))
        val h21 = main__(arrayOf(H0, S1, "chain", "#", "post", "inline", "h21"))
        /*val h20 =*/ main_(arrayOf(H0, S0, "chain", "#", "post", "inline", "h20"))

        // h0 -> h1 --> h21
        //          \-> h20

        // no double spend
        main__(arrayOf(H0, S0, "chain", "#", "post", "inline", "h30")).let {
            //assert(it == "backs must be accepted")
        }

        // h0 -> h1 --> h21
        //          \-> h20 -> h30

        main_(arrayOf(H0, "host", "now", "${3*hour}"))
        main_(arrayOf(H0, S0, "chain", "#", "post", "inline", "h40"))

        // h0 -> h1 --> h20 -> h30 -> h40
        //          \-> h21

        main_(arrayOf(H0, "host", "now", "${6*hour}"))
        /*val l50 =*/ main_(arrayOf(H0, S0, "chain", "#", "like", h21, "--why=l50"))

        // h0 -> h1 --> h21
        //          \-> h20 -> h30 -> h40 -> l50

        main_(arrayOf(H0, "host", "now", "${9*hour}"))

        val h61 = main__(arrayOf(H0, S1, "chain", "#", "post", "inline", "h61"))

        // h0 -> h1 --> h21 -----------------------> h61
        //          \-> h20 -> h30 -> l40 -> l50 /

        /*val l60 =*/ main_(arrayOf(H0, S0, "chain", "#", "like", h61, "--why=l60"))

        // h0 -> h1 --> h21 -----------------------> h61
        //          \-> h20 -> h30 -> l40 -> l50 /-> l60

        main_(arrayOf(H0, "host", "now", "${34*hour}"))
        /*val h7 =*/ main_(arrayOf(H0, S1, "chain", "#", "post", "inline", "h7"))

        // h0 -> h1 --> h21 ---------------------\-> h61 --> h7
        //          \-> h20 -> h30 -> l40 -> l50 /-> l60 -/

        // removes h21 (wont remove anything)
        /*val l- =*/ main_(arrayOf(H0, S0, "chain", "#", "dislike", h21, "--why=dislike"))

        // h0 -> h1 --> h21 -----------------------> h61 --> h7
        //          \-> h20 -> h30 -> l40 -> l50 /-> l60 -/-> l-

        main__(arrayOf(H0, "chain", "#", "heads", "linked")).let {
            assert(!it.contains("2_"))
        }
        main__(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert(it.isEmpty())
        }

        main_(arrayOf(H0, "host", "now", "${40*hour}"))

        main__(arrayOf(H0, "chain", "#", "heads", "linked")).let { str ->
            str.split(' ').let {
                assert(it.size == 1) { it.size }
                it.forEach {
                    assert(it.startsWith("9_"))
                }
            }
        }
    }

    @Test
    fun m15_rejected() {
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M150/")) }
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M151/", "8331")) }
        Thread.sleep(200)
        main_(arrayOf(H0, "chains", "join", "#"))
        main_(arrayOf(H1, "chains", "join", "#"))

        main_(arrayOf(H0, "host", "now", "0"))
        main_(arrayOf(H1, "host", "now", "0"))

        main_(arrayOf(H0, S0, "chain", "#", "post", "inline", "0@h1"))
        val h2 = main__(arrayOf(H0, S1, "chain", "#", "post", "inline", "1@h2"))
        main_(arrayOf(H0, S0, "chain", "#", "like", "--why=0@l2", h2))

        main_(arrayOf(H1, "peer", "localhost:$PORT_8330", "recv", "#"))

        // HOST-0
        // h0 <- 0@h1 <- 1@h2 <- 0@l2

        // HOST-1
        // h0 <- 0@h1 <- 1@h2 <- 0@l2

        // l3
        main_(arrayOf(H0, S0, "chain", "#", "dislike", h2, "--why=0@l3"))

        // HOST-0
        // h0 <- 0@h1 <- 1@h2 <- 0@l2 <- 0@l3-

        main_(arrayOf(H0, "host", "now", "${5*hour}"))
        main_(arrayOf(H1, "host", "now", "${5*hour}"))

        main__(arrayOf(H0, "chain", "#", "heads", "linked")).let { str ->
            str.split(' ').let {
                assert(it.size == 1) { it.size }
                it.forEach { v -> assert(v.startsWith("4_")) }
            }
        }
        main__(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert(it.isEmpty())
        }

        main__(arrayOf(H1, "chain", "#", "heads", "linked")).let { str ->
            str.split(' ').let {
                assert(it.size == 1) { it.size }
                it.forEach { v -> assert(v.startsWith("3_")) }
            }
        }
        main__(arrayOf(H1, "chain", "#", "heads", "blocked")).let {
            assert(it.isEmpty())
        }
        main_(arrayOf(H0, "host", "now", "${25*hour}"))
        main_(arrayOf(H1, "host", "now", "${25*hour}"))

        main__(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert(it.isEmpty())
        }

        main_(arrayOf(H1, S1, "chain", "#", "post", "inline", "1@h3"))

        // HOST-0
        // h0 <- 0@h1 <- 1@h2 <- 0@l2 <- 0@l3-

        // HOST-1
        // h0 <- 0@h1 <- 1@h2 <- 0@l2 <- 1@h3

        main__(arrayOf(H1, "chain", "#", "heads", "linked")).let { str ->
            str.split(' ').let {
                assert(it.size == 1) { it.size }
                it.forEach { v -> assert(v.startsWith("4_")) }
            }
        }

        // send H1 -> H0
        // ~1@h3 will be rejected b/c 1@h2 is rejected in H0~
        main__(arrayOf(H1, "peer", "localhost:$PORT_8330", "send", "#")).let {
            assert(it.contains("1 / 1"))
        }

        // l4: try again after like // like will be ignored b/c >24h
        main_(arrayOf(H0, S0, "chain", "#", "like", h2))

        // HOST-0
        // h0 <- 0@h1 <- 0@l2 <-- 0@l3- <- 0@l4
        //            <- 1@h2 <-\ 1@h3     /
        //                  \-------------/

        // HOST-1
        // h0 <- 0@h1 <- 0@l2 |
        //            <- 1@h2 | <- 1@h3

        main__(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert(it.isEmpty())
        }
        main__(arrayOf(H1, "chain", "#", "heads", "blocked")).let {
            assert(it.isEmpty())
        }
    }

    @Test
    fun m16_likes_fronts () {
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M16/")) }
        Thread.sleep(200)
        main_(arrayOf(H0, "chains", "join", "#"))

        main_(arrayOf(H0, "host", "now", "0"))

        main_(arrayOf(H0, S0, "chain", "#", "post", "inline", "0@h1"))
        val h2 = main__(arrayOf(H0, S1, "chain", "#", "post", "inline", "1@h2"))
        main_(arrayOf(H0, S0, "chain", "#", "like", h2, "--why=0@l2"))

        // HOST-0
        // h0 <- 0@h1 <-- 0@l2
        //            <- 1@h2

        main__(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert(it.isEmpty())
        }

        main_(arrayOf(H0, "host", "now", "${3*hour}"))

        // l4 dislikes h2: h2 should remain accepted b/c h2<-l3
        main_(arrayOf(H0, S0, "chain", "#", "post", "inline", "0@h3"))
        main_(arrayOf(H0, S0, "chain", "#", "dislike", h2, "--why=0@l3"))

        // HOST-0
        // h0 <- 0@h1 <-- 0@l2 \ 0@h3 -- 0@l3
        //            <- 1@h2  /

        main__(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert(it.isEmpty())
        }
        main__(arrayOf(H0, "chain", "#", "heads", "linked")).let {
            assert(it.startsWith("5_"))
        }
    }

    @Test
    fun m17_likes_day () {
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M17/")) }
        Thread.sleep(200)
        main_(arrayOf(H0, "chains", "join", "#"))

        main_(arrayOf(H0, "host", "now", "0"))

        main_(arrayOf(H0, S0, "chain", "#", "post", "inline", "0@h1"))
        val h2 = main__(arrayOf(H0, S1, "chain", "#", "post", "inline", "1@h2"))
        main_(arrayOf(H0, S0, "chain", "#", "like", h2))

        // HOST-0
        // h0 <- 0@h1 <-- 0@l2
        //            <- 1@h2

        main__(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert(it.isEmpty())
        }

        main_(arrayOf(H0, "host", "now", "${25*hour}"))

        // l4 dislikes h2: h2 should remain accepted b/c (l3-h2 > 24h)
        main_(arrayOf(H0, S0, "chain", "#", "dislike", h2))

        // HOST-0
        // h0 <- 0@h1 <-- 0@l2 <-- 0@h3 <- 0@l4
        //            <- 1@h2 <-/

        main__(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert(it.isEmpty())
        }
        main__(arrayOf(H0, "chain", "#", "heads", "linked")).let {
            assert(it.startsWith("4_"))
        }
    }

    @Test
    fun m18_remove () {
        thread { main_(arrayOf("host", "start", "/tmp/freechains/tests/M18/")) }
        Thread.sleep(200)
        main_(arrayOf(H0, "chains", "join", "#"))

        main_(arrayOf(H0, "host", "now", "0"))

        val h1 = main__(arrayOf(H0, S0, "chain", "#", "post", "inline", "0@h1"))
        val h2 = main__(arrayOf(H0, S1, "chain", "#", "post", "inline", "1@h2"))

        // h0 <- 0@h1 <- 1@h2

        main__(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert(it.startsWith("2_"))
        }
        main__(arrayOf("chain", "#", "heads", "all")).let { list ->
            list.split(' ').toTypedArray().let {
                assert(it.size == 1)
                assert(it[0].startsWith("2_"))
            }
        }

        main_(arrayOf(H0, "chain", "#", "remove", h1)).let { (ok,_) ->
            assert(!ok) // "! can only remove blocked block")
        }

        main__(arrayOf(H0, "chain", "#", "remove", h2)).let {
            assert(it == "")
        }

        // h0 <- 0@h1

        main__(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert(it.isEmpty())
        }
        main__(arrayOf("chain", "#", "heads", "all")).let { list ->
            list.split(' ').toTypedArray().let {
                assert(it.size == 1)
                assert(it[0].startsWith("1_"))
            }
        }
    }
}

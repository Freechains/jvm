import com.goterl.lazycode.lazysodium.LazySodiumJava
import com.goterl.lazycode.lazysodium.SodiumJava
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
import kotlin.concurrent.thread


@Serializable
data class MeuDado(val v: String)

/*
 *  TODO:
 *  - 948 -> 852 -> 841 -> 931 -> 1041 -> 1101 -> 980 -> (no tests) -> 736 LOC
 *  - 10556 -> 10557 -> 10553 -> 10553 KB
 *  - chain locks
 *  - all use cases (chain cfg e usos da industria)
 *  - freechains crypto criptografar payloads
 *    - melhor seria opcao --encrypt no put, gravando flag no bloco (o get faria decrypt automaticamente)
 *  - sistema de reputacao
 *  - testes antigos
 *  - RX Kotlin
 *  - pipes / filtros
 *  - freechains chain remove
 *  - freechains host configure (json)
 *    - peer/chain configurations in host
 *    - freechains host restart
 *  - Future:
 *  - Xfreechains
 *    - chain xtraverse
 *    - chain xlisten
 *  - Android WiFi Direct
 *  - crypto host-to-host
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
        val chain1 = Chain("/tmp/freechains/tests/local/chains/", "/uerj", arrayOf("secret","",""))
        //println("Chain /uerj: ${chain1.toHash()}")
        chain1.save()
        val chain2 = host1.loadChain(chain1.name)
        assertThat(chain1.hashCode()).isEqualTo(chain2.hashCode())
    }

    @Test
    fun b2_node () {
        val chain = Chain("/tmp/freechains/tests/local/chains/", "/uerj",arrayOf("","",""))
        val node = chain.newNode(NodeHashable(0,"utf8","111", arrayOf(chain.toGenHash())))
        chain.saveNode(node)
        val node2 = chain.loadNodeFromHash(node.hash)
        assertThat(node.hashCode()).isEqualTo(node2.hashCode())
    }

    @Test
    fun c1_publish () {
        val host = Host_load("/tmp/freechains/tests/local/")
        val chain = host.createChain("/ceu", arrayOf("","",""))
        val n1 = chain.publish("utf8","aaa", 0)
        val n2 = chain.publish("utf8","bbb", 1)
        val n3 = chain.publish("utf8","ccc", 2)

        chain.assertNode(n3)
        var ok = false
        try {
            val n = n3.copy(hashable = n3.hashable.copy(payload = "xxx"))
            chain.assertNode(n)
        } catch (e: Throwable) {
            ok = true
        }
        assert(ok)

        assert(chain.containsNode(chain.toGenHash()))
        //println(n1.toHeightHash())
        assert(chain.containsNode(n1.hash))
        assert(chain.containsNode(n2.hash))
        assert(chain.containsNode(n3.hash))
        assert(!chain.containsNode("2_........"))
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
        val src_chain = src.createChain("/d3", arrayOf("secret","",""))
        src_chain.publish("utf8","aaa", 0)
        src_chain.publish("utf8","bbb", 0)
        thread { daemon(src) }

        // DESTINY
        val dst = Host_create("/tmp/freechains/tests/dst/", 8331)
        dst.createChain("/d3", arrayOf("secret","",""))
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
        val chain = Chain("/tmp/freechains/tests/local/chains/", "/graph", arrayOf("secret","",""))
        chain.save()
        val genesis = Node(
            NodeHashable(0,"utf8", "", emptyArray()),
            emptyArray(),"", chain.toGenHash()
        )
        chain.saveNode(genesis)

        val a1 = chain.newNode(NodeHashable(0,"utf8", "a1", arrayOf(chain.toGenHash())))
        val b1 = chain.newNode(NodeHashable(0,"utf8", "b1", arrayOf(chain.toGenHash())))
        chain.saveNode(a1)
        chain.saveNode(b1)
        chain.reheads(a1)
        chain.reheads(b1)

        //val ab2 =
        chain.publish("utf8","ab2", 0)

        val b2 = chain.newNode(NodeHashable(0,"utf8","b2", arrayOf(b1.hash)))
        chain.saveNode(b2)
        chain.reheads(b2)

        chain.publish("utf8","ab3", 0)
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
        val h1_chain = h1.createChain("/xxx", arrayOf("","",""))
        h1_chain.publish("utf8","h1_1", 0)
        h1_chain.publish("utf8","h1_2", 0)

        val h2 = Host_create("/tmp/freechains/tests/h2/", 8331)
        val h2_chain = h2.createChain("/xxx", arrayOf("","",""))
        h2_chain.publish("utf8","h2_1", 0)
        h2_chain.publish("utf8","h2_2", 0)

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
        main(arrayOf("chain","create","/xxx"))

        main(arrayOf("chain","genesis","/xxx"))
        main(arrayOf("chain","heads","/xxx"))

        main(arrayOf("chain","put","/xxx","inline","utf8","aaa"))
        main(arrayOf("chain","put","/xxx","file","utf8","/tmp/freechains/tests/M1/host"))

        main(arrayOf("chain","genesis","/xxx"))
        main(arrayOf("chain","heads","/xxx"))

        main(arrayOf("chain","get","--host=localhost:8330","/xxx", "0_765CB5D42FDDFFA8446D755F0BD6F868F3FC65EBDD6BDCE91CB743AE2EA878EE"))
        main(arrayOf("chain","get","/xxx", "0_765CB5D42FDDFFA8446D755F0BD6F868F3FC65EBDD6BDCE91CB743AE2EA878EE"))

        main(arrayOf("chain","put","/xxx","file","base64","/bin/cat"))
        main(arrayOf("host","stop"))
        // TODO: check genesis 2x, "aaa", "host"
        // $ cat /tmp/freechains/tests/M1/chains/xxx/*
    }

    @Test
    fun m2_crypto () {
        //a_reset()
        main(arrayOf("host","create","/tmp/freechains/tests/M2/"))
        thread {
            main(arrayOf("host","start","/tmp/freechains/tests/M2/"))
        }
        Thread.sleep(100)
        val lazySodium = LazySodiumJava(SodiumJava())
        val kp : KeyPair = lazySodium.cryptoSignKeypair()
        val pk : Key = kp.getPublicKey()
        val sk : Key = kp.getSecretKey()
        assert(lazySodium.cryptoSignKeypair(pk.getAsBytes(), sk.getAsBytes()))
        //println("TSTTST: ${pk.asHexString} // ${sk.asHexString}")
        main(arrayOf("crypto","create","shared","senha secreta"))
        main(arrayOf("crypto","create","pubpvt","senha secreta"))

        val msg = "mensagem secreta"
        val nonce = lazySodium.nonce(SecretBox.NONCEBYTES)
        val key = Key.fromHexString("B07CFFF4BE58567FD558A90CD3875A79E0876F78BB7A94B78210116A526D47A5")
        val encrypted = lazySodium.cryptoSecretBoxEasy(msg, nonce, key)
        //println("nonce=${lazySodium.toHexStr(nonce)} // msg=$encrypted")
        val decrypted = lazySodium.cryptoSecretBoxOpenEasy(encrypted, nonce, key)
        assert(msg == decrypted)
    }

    @Test
    fun m2_crypto_publish () {
        //a_reset()
        val host = Host_load("/tmp/freechains/tests/M2/")

        val c1 = host.createChain("/sym", arrayOf("secret","",""))
        val n1 = c1.publish("utf8","aaa", 0)
        c1.assertNode(n1)
        var ok1 = false
        try {
            val c = c1.copy(keys=arrayOf("wrong","",""))
            c.assertNode(n1)
        } catch (e: Throwable) {
            ok1 = true
        }
        assert(ok1)

        val c2 = host.createChain("/asy", arrayOf("","3CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322","6F99999751DE615705B9B1A987D8422D75D16F5D55AF43520765FA8C5329F7053CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322"))
        val n2 = c2.publish("utf8","aaa", 0)
        c2.assertNode(n2)
        val cx = c2.copy(keys= arrayOf("","3CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322",""))
        cx.assertNode(n2)
        var ok2 = false
        try {
            val cz = c2.copy(keys= arrayOf("","4CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322",""))
            cz.assertNode(n2)
        } catch (e: Throwable) {
            ok2 = true
        }
        assert(ok2)
    }
}

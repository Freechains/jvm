import com.goterl.lazycode.lazysodium.LazySodiumJava
import com.goterl.lazycode.lazysodium.SodiumJava
import com.goterl.lazycode.lazysodium.utils.Key
import com.goterl.lazycode.lazysodium.utils.KeyPair
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer.Alphanumeric
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Test

import java.io.File

import kotlin.concurrent.thread

import org.freechains.common.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration

@Serializable
data class MeuDado(val v: String)

/*
 *  TODO:
 *  - 948 -> 852 -> 841 -> 931 -> 1041 LOC
 *  - 10554 KB
 *  - all use cases (chain cfg e usos da industria)
 *  - mover testes
 *  - descobrir println(null), freechains crypto p/ criar as chaves e criptografar payloads
 *  - android again
 *  - chain locks
 *  - testes antigos
 *  - crypto (asym e host)
 *  - RX Kotlin
 *  - pipes / filtros
 *  - freechains chain remove
 *  - freechains host configure (json)
 *    - peer/chain configurations in host
 *    - freechains host restart
 *  - Xfreechains (lucas)
 *    - chain xtraverse
 *    - chain xlisten
 *  - Android WiFi Direct
 */

@TestMethodOrder(Alphanumeric::class)
class Tests {

    @Test
    fun aaa () {
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
    fun aaaa () {
        val lazySodium = LazySodiumJava(SodiumJava())
        val kp : KeyPair = lazySodium.cryptoSignKeypair()
        val pk : Key = kp.getPublicKey()
        val sk : Key = kp.getSecretKey()
        assert(lazySodium.cryptoSignKeypair(pk.getAsBytes(), sk.getAsBytes()))
        println(pk.getAsHexString())
        println(sk.getAsHexString())
    }


    @Test
    fun a_reset () {
        assert( File("/tmp/freechains/tests/").deleteRecursively() )
    }

    @Test
    fun a1_hash () {
        val x = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val y = byteArrayOf(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31)
        assert(x == y.toHexString())
    }

    @Test
    fun b1_chain () {
        val host1 = Host_create("/tmp/freechains/tests/local/")
        val chain1 = Chain("/tmp/freechains/tests/local/chains/", "/uerj", 0, arrayOf("secret","",""))
        //println("Chain /uerj/0: ${chain1.toHash()}")
        chain1.save()
        val chain2 = host1.loadChain(chain1.toPath())
        assertThat(chain1.hashCode()).isEqualTo(chain2.hashCode())
    }

    @Test
    fun b2_node () {
        val chain = Chain("/tmp/freechains/tests/local/chains/", "/uerj",0, arrayOf("","",""))
        val node = Node_new (
            NodeHashable(0,0,"utf8","111", arrayOf(chain.toGenHash())),
            emptyArray(), 0, arrayOf("","","")
        )
        chain.saveNode(node)
        val node2 = chain.loadNodeFromHash(node.hash)
        assertThat(node.hashCode()).isEqualTo(node2.hashCode())
    }

    @Test
    fun c1_publish () {
        val host = Host_load("/tmp/freechains/tests/local/")
        val chain = host.createChain("/ceu/10", arrayOf("","",""))
        val n1 = chain.publish("utf8","aaa", 0)
        val n2 = chain.publish("utf8","bbb", 1)
        val n3 = chain.publish("utf8","ccc", 2)

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
        val src_chain = src.createChain("/d3/5", arrayOf("secret","",""))
        src_chain.publish("utf8","aaa", 0)
        src_chain.publish("utf8","bbb", 0)
        thread { daemon(src) }

        // DESTINY
        val dst = Host_create("/tmp/freechains/tests/dst/", 8331)
        dst.createChain("/d3/5", arrayOf("secret","",""))
        thread { daemon(dst) }
        Thread.sleep(100)

        main(arrayOf("chain","send","/d3/5","localhost:8331"))
        Thread.sleep(100)

        main(arrayOf("--host=localhost:8331","host","stop"))
        main(arrayOf("host","stop"))
        Thread.sleep(100)

        // TODO: check if dst == src
        // $ diff -r /tmp/freechains/tests/dst/ /tmp/freechains/tests/src/
    }

    @Test
    fun e1_graph () {
        val chain = Chain("/tmp/freechains/tests/local/chains/", "/graph",0, arrayOf("secret","",""))
        chain.save()
        val genesis = Node(
            NodeHashable(0,0,"utf8", "", emptyArray()),
            emptyArray(),"", chain.toGenHash()
        )
        chain.saveNode(genesis)

        val a1 = Node_new (
            NodeHashable(0,0,"utf8", "a1", arrayOf(chain.toGenHash())),
            emptyArray(),0, arrayOf("","","")
        )
        val b1 = Node_new (
            NodeHashable(0,0,"utf8", "b1", arrayOf(chain.toGenHash())),
            emptyArray(),0, arrayOf("","","")
        )
        chain.saveNode(a1)
        chain.saveNode(b1)
        chain.reheads(a1)
        chain.reheads(b1)

        //val ab2 =
        chain.publish("utf8","ab2", 0)

        val b2 = Node_new (
            NodeHashable(0,0,"utf8","b2", arrayOf(b1.hash)),
            emptyArray(), 0, arrayOf("","","")
        )
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
        val h1_chain = h1.createChain("/xxx/0", arrayOf("","",""))
        h1_chain.publish("utf8","h1_1", 0)
        h1_chain.publish("utf8","h1_2", 0)

        val h2 = Host_create("/tmp/freechains/tests/h2/", 8331)
        val h2_chain = h2.createChain("/xxx/0", arrayOf("","",""))
        h2_chain.publish("utf8","h2_1", 0)
        h2_chain.publish("utf8","h2_2", 0)

        Thread.sleep(100)
        thread { daemon(h1) }
        thread { daemon(h2) }
        Thread.sleep(100)
        main(arrayOf("--host=localhost:8331","chain","send","/xxx/0","localhost"))
        Thread.sleep(100)
        main(arrayOf("--host=localhost:8331","host","stop"))
        main(arrayOf("host","stop"))
        Thread.sleep(100)

        // TODO: check if 8332 (h2) < 8331 (h1)
        // $ diff -r /tmp/freechains/tests/h1 /tmp/freechains/tests/h2/
    }

    @Test
    fun m1_args () {
        a_reset()
        main(arrayOf("host","create","/tmp/freechains/tests/M1/"))
        thread {
            main(arrayOf("host","start","/tmp/freechains/tests/M1/"))
        }
        Thread.sleep(100)
        main(arrayOf("chain","create","/xxx/0"))

        main(arrayOf("chain","genesis","/xxx/0"))
        main(arrayOf("chain","heads","/xxx/0"))

        main(arrayOf("chain","put","/xxx/0","inline","utf8","aaa"))
        main(arrayOf("chain","put","/xxx/0","file","utf8","/tmp/freechains/tests/M1/host"))

        main(arrayOf("chain","genesis","/xxx/0"))
        main(arrayOf("chain","heads","/xxx/0"))

        main(arrayOf("chain","get","--host=localhost:8330","/xxx/0", "0_360B889B0EC78AB3A47F165E12348D4209653905191CEB5ED4C9C737DFCF0430"))
        main(arrayOf("chain","get","/xxx/0", "0_360B889B0EC78AB3A47F165E12348D4209653905191CEB5ED4C9C737DFCF0430"))

        main(arrayOf("chain","put","/xxx/0","file","base64","/bin/cat"))
        main(arrayOf("host","stop"))
        // TODO: check genesis 2x, "aaa", "host"
        // $ cat /tmp/freechains/tests/M1/chains/xxx/0/*
    }
}

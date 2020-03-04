package org.freechains.common

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

import com.goterl.lazycode.lazysodium.interfaces.PwHash
import com.goterl.lazycode.lazysodium.utils.Key
import org.freechains.platform.lazySodium
import java.lang.Long.max
import java.time.Instant
import java.util.*
import kotlin.collections.HashSet

class Daemon (host : Host) {
    val listenLists = mutableMapOf<String,MutableSet<DataOutputStream>>()
    val server = ServerSocket(host.port)
    val local = host

    fun getLock (chain: Chain? = null) : String {
        return local.root.intern() + (if (chain == null) "" else chain.hash.intern())
    }

    fun daemon () {
        //System.err.println("host start: $host")
        while (true) {
            try {
                val remote = server.accept()
                System.err.println("remote connect: $local <- ${remote.inetAddress.hostAddress}")
                thread {
                    try {
                        handle(remote)
                    } catch (e: Throwable) {
                        remote.close()
                        System.err.println(
                            e.message ?: e.toString()
                        )
                        //println(e.stackTrace.contentToString())
                    }
                }
            } catch (e: SocketException) {
                assert(e.message == "Socket closed")
                break
            }
        }
    }

    fun signal (chain: String, n: Int) {
        val has = synchronized (listenLists) { listenLists.containsKey(chain) }
        if (has) {
            val wrs = synchronized (listenLists) { listenLists[chain]!!.toList() }
            for (wr in wrs) {
                try {
                    wr.writeLineX(n.toString())
                } catch (e: Throwable) {
                    synchronized (listenLists) { listenLists[chain]!!.remove(wr) }
                }
            }
        }
    }

    fun handle (remote: Socket) {
        val reader = DataInputStream(remote.getInputStream()!!)
        val writer = DataOutputStream(remote.getOutputStream()!!)
        var shouldClose = true
        val ln = reader.readLineX()
        when (ln) {
            "FC host stop" -> {
                writer.writeLineX("true")
                server.close()
                System.err.println("host stop: $local")
            }
            "FC host now" -> {
                val now= reader.readLineX().toLong()
                setNow(now)
                writer.writeLineX("true")
                System.err.println("host now: $now")
            }
            "FC crypto create" -> {
                fun pwHash (pwd: ByteArray) : ByteArray {
                    val out  = ByteArray(32)                       // TODO: why?
                    val salt = ByteArray(PwHash.ARGON2ID_SALTBYTES)     // all zeros
                    assert(lazySodium.cryptoPwHash(
                        out,out.size, pwd,pwd.size, salt,
                        PwHash.OPSLIMIT_INTERACTIVE, PwHash.MEMLIMIT_INTERACTIVE, PwHash.Alg.getDefault()
                    ))
                    return out
                }

                val type  = reader.readLineX()
                val plain = reader.readLineX().toByteArray()
                val pwh   = pwHash(plain)

                when (type) {
                    "shared" -> {
                        writer.writeLineX(Key.fromBytes(pwh).asHexString)
                    }
                    "pubpvt" -> {
                        val keys = lazySodium.cryptoSignSeedKeypair(pwh)
                        //println("PUBPVT: ${keys.publicKey.asHexString} // ${keys.secretKey.asHexString}")
                        writer.writeLineX(keys.getPublicKey().getAsHexString())
                        writer.writeLineX(keys.getSecretKey().getAsHexString())
                    }
                }
            }
            "FC chain join" -> {
                val name= reader.readLineX().nameCheck()
                val type= reader.readLineX()
                val crypto = when (type) {
                    "" -> null
                    "shared" -> {
                        val key = reader.readLineX()
                        Shared(if (key.isEmpty()) null else key)
                    }
                    "pubpvt" -> {
                        val oonly= reader.readLineX().toBoolean()
                        val pub= reader.readLineX()
                        val pvt= reader.readLineX()
                        PubPvt(oonly, pub, if (pvt.isEmpty()) null else pvt)
                    }
                    else -> error("bug found")
                }
                val chain = synchronized (getLock()) {
                    local.joinChain(name,crypto)
                }
                writer.writeLineX(chain.hash)
                System.err.println("chain join: $name (${chain.hash})")
            }
            "FC chain listen" -> {
                val name= reader.readLineX().nameCheck()
                synchronized (listenLists) {
                    if (! listenLists.containsKey(name)) {
                        listenLists[name] = mutableSetOf()
                    }
                    listenLists[name]!!.add(writer)
                }
                shouldClose = false
            }
            else -> {
                assert(ln.substring(0,8) == "FC chain")
                val name  = reader.readLineX().nameCheck()
                val chain = synchronized (getLock()) {
                    local.loadChain(name)
                }
                synchronized (getLock(chain)) {
                    when (ln) {
                        "FC chain genesis" -> {
                            val hash  = chain.getGenesis()
                            writer.writeLineX(hash)
                            System.err.println("chain genesis: $hash")
                        }
                        "FC chain heads" -> {
                            for (head in chain.heads) {
                                writer.writeLineX(head)
                            }
                            writer.writeLineX("")
                            System.err.println("chain heads: ${chain.heads}")
                        }
                        "FC chain get" -> {
                            val dir = reader.readLineX()
                            val hash = reader.readLineX()
                            assert(dir == "blocks" || dir == "tines")

                            val dec = chain.isSharedWithKey() || (chain.crypto is PubPvt && chain.crypto.pvt != null)
                            val blk   = chain.loadBlock(dir,hash,dec)
                            val json  = blk.toJson()

                            assert(json.length <= Int.MAX_VALUE)
                            writer.writeBytes(json)
                            //writer.writeLineX("\n")
                            System.err.println("chain get: $hash")
                        }
                        "FC chain reps" -> {
                            val time = reader.readLineX()
                            val ref = reader.readLineX()

                            val likes =
                                if (ref.hashIsBlock()) {
                                    chain.getPostRep(ref)
                                } else {
                                    chain.getPubRep(ref, time.nowToTime())
                                }

                            writer.writeLineX(likes.toString())
                            System.err.println("chain reps: $likes")
                        }
                        "FC chain tine list" -> {
                            chain.loadTines()
                                .forEach { writer.writeLineX(it) }
                            writer.writeLineX("")
                        }
                        "FC chain accept" -> {
                            val hash = reader.readLineX()
                            when {
                                (chain.containsBlock("rems",hash)) -> {
                                    val rem = chain.loadBlock("rems", hash, false)
                                    chain.blockChain(rem)
                                    chain.remBlock("rems", rem.hash)
                                    writer.writeLineX("true")
                                }
                                (chain.containsBlock("tines",hash)) -> {
                                    val tine = chain.loadBlock("tines", hash, false)
                                    chain.blockChain(tine)
                                    chain.remBlock("tines", tine.hash)
                                    writer.writeLineX("true")
                                }
                                (chain.containsBlock("blocks",hash)) -> {
                                    val blk = chain.loadBlock("blocks", hash, false)
                                    chain.saveBlock("blocks",blk.copy(accepted = true))
                                    writer.writeLineX("true")
                                }
                                else  -> {
                                    writer.writeLineX("false")
                                }
                            }
                        }
                        "FC chain remove" -> {
                            val hash = reader.readLineX()
                            when {
                                (chain.containsBlock("blocks",hash)) -> {
                                    chain.blockRemove(hash)
                                    writer.writeLineX("true")
                                }
                                (chain.containsBlock("tines",hash)) -> {
                                    chain.moveBlock("tines", "rems", hash)
                                    writer.writeLineX("true")
                                }
                                else -> {
                                    writer.writeLineX("false")
                                }
                            }
                        }
                        "FC chain post" -> {
                            val time = reader.readLineX()
                            val like   = reader.readLineX().toInt()
                            val cod  = reader.readLineX()
                            val cry = reader.readLineX().toBoolean() or chain.isSharedWithKey()

                            val cods = cod.split(' ')
                            val pay  = reader.readLinesX(cods.getOrNull(1) ?: "")

                            val refs = reader.readLinesX()
                            val sig  = reader.readLineX()   // "" / "chain" / <pvt>

                            val refs_ = if (refs == "") emptyArray() else refs.split('\n').toTypedArray()
                            val likes =
                                if (refs_.isEmpty()) {
                                    arrayOf<Like?>(null)
                                } else {
                                    if (refs_[0].hashIsBlock()) {
                                        // refs a post
                                        val blk = chain.loadBlock("blocks", refs_[0], false)
                                        val l1 = arrayOf(Like(like/2, LikeType.POST, refs_[0]))
                                        if (blk.sign == null)
                                            l1
                                        else
                                            l1.plus(Like(like/2, LikeType.PUBKEY, blk.sign.pub))
                                    } else {
                                        // refs a pubkey
                                        arrayOf (
                                            Like(like, LikeType.PUBKEY, refs_[0])
                                        )
                                    }
                                }

                            val hashes = mutableListOf<Hash>()
                            for (l in likes) {
                                val blk = chain.blockNew (
                                    if (sig.isEmpty()) null else sig,
                                    BlockImmut (
                                        max (
                                            time.nowToTime(),
                                            chain.heads.map { chain.loadBlock("blocks",it,false).immut.time }.max()!!
                                                // TODO: +1 prevents something that happened after to occur simultaneously (also, problem with TODO???)
                                        ),
                                        l,
                                        cods[0],
                                        cry,
                                        pay,
                                        refs_,
                                        emptyArray()
                                    )
                                )
                                hashes.add(blk.hash)
                            }
                            println("post: $hashes")

                            val ret = hashes.joinToString(" ")
                            writer.writeLineX(ret)
                            System.err.println("chain post: $ret")

                            thread {
                                signal(name,1)
                            }
                        }
                        "FC chain send" -> {
                            val host_ = reader.readLineX()

                            val (host,port) = host_.hostSplit()

                            val socket = Socket(host, port)
                            val (nmin,nmax) = socket.chain_send(chain)
                            System.err.println("chain send: $name ($nmin/$nmax)")
                            writer.writeLineX("$nmin / $nmax")
                        }
                        "FC chain recv" -> {
                            val (nmin,nmax) = remote.chain_recv(chain)
                            System.err.println("chain recv: $name: ($nmin/$nmax)")
                            thread {
                                signal(name, nmin)
                            }
                            //writer.writeLineX(ret)
                        }
                        else -> { error("$ln: invalid header type") }
                    }
                }
            }
        }
        if (shouldClose) {
            Thread.sleep(1000)
            remote.close()
        }
    }
}

fun Socket.chain_send (chain: Chain) : Pair<Int,Int> {
    val reader = DataInputStream(this.getInputStream()!!)
    val writer = DataOutputStream(this.getOutputStream()!!)

    // - receives most recent timestamp
    // - DFS in heads
    //   - asks if contains hash
    //   - aborts path if reaches timestamp+24h
    //   - pushes into toSend
    // - sends toSend

    writer.writeLineX("FC chain recv")
    writer.writeLineX(chain.name)

    val visited = HashSet<Hash>()
    val toSend  = ArrayDeque<Hash>()
    var Nmin    = 0
    var Nmax    = 0
    //println("[send] $maxTime")

    // for each local head
    val n1 = chain.heads.size
    writer.writeLineX(n1.toString())                              // 1
    for (head in chain.heads) {
        val pending = ArrayDeque<Hash>()
        pending.push(head)

        // for each head path of blocks
        while (pending.isNotEmpty()) {
            val hash = pending.pop()
            if (visited.contains(hash)) {
                continue
            }
            visited.add(hash)

            val blk = chain.loadBlock("blocks", hash,false)

            writer.writeLineX(hash)                               // 2: asks if contains hash
            val has = reader.readLineX().toBoolean()    // 3: receives yes or no
            if (has) {
                continue                             // already has: finishes subpath
            }

            // sends this one and visits children
            toSend.push(hash)
            for (back in blk.immut.backs) {
                pending.push(back)
            }
        }

        writer.writeLineX("")                     // 4: will start sending nodes
        writer.writeLineX(toSend.size.toString())    // 5: how many
        val n2 = toSend.size
        while (toSend.isNotEmpty()) {
            val hash = toSend.pop()
            val blk = chain.loadBlock("blocks", hash,false)
            blk.fronts.clear()
            writer.writeBytes(blk.toJson())          // 6
            writer.writeLineX("\n")
        }
        val n2_ = reader.readLineX().toInt()    // 7: how many blocks again
        assert(n2 >= n2_)
        Nmin += n2_
        Nmax += n2
    }
    val n1_ = reader.readLineX().toInt()        // 8: how many heads again
    assert(n1 == n1_)

    return Pair(Nmin,Nmax)
}

fun Socket.chain_recv (chain: Chain) : Pair<Int,Int> {
    val reader = DataInputStream(this.getInputStream()!!)
    val writer = DataOutputStream(this.getOutputStream()!!)

    // - sends most recent timestamp
    // - answers if contains each node
    // - receives all

    var Nmax = 0
    var Nmin = 0
    val now = getNow()
    //println("[recv] $now")

    // for each remote head
    val n1 = reader.readLineX().toInt()        // 1
    for (i in 1..n1) {
        // for each head path of blocks
        while (true) {
            val hash = reader.readLineX()   // 2: receives hash in the path
            //println("[recv] $hash")
            if (hash.isEmpty()) {                   // 4
                break                               // nothing else to answer
            } else {
                val has = chain.containsBlock("blocks",hash)
                writer.writeLineX(has.toString())   // 3: have or not block
            }
        }

        // receive blocks
        val n2 = reader.readLineX().toInt()    // 5
        Nmax += n2
        //println("[recv] $n2")

        xxx@for (j in 1..n2) {
            val blk = reader.readLinesX().jsonToBlock().copy(accepted = false) // 6

            try {
                chain.blockAssert(blk)
            } catch (e: Throwable) {
                continue
            }

            //println("[recv] ${blk.hash}")

            if (
                !chain.isConsolidated(blk) &&
                (
                    blk.immut.time <= now-T2H_past          ||  // too late
                    blk.sign == null                        ||  // no sig
                    chain.getPubRep(blk.sign.pub,now) <= 0      // no rep
                )
            ) {
                // quarentine noob/late block
                // enqueue only if backs are ok (otherwise, back is also enqueued)
                // TODO: when back was enqueued previously, the host should signal the peer
                //  to avoid this situation (it may send many other wrong blocks)
                if (chain.backsCheck(blk)) {
                    chain.saveBlock("tines", blk)
                }
                // otherwise just ignore
            } else {
                var inc = 1
                try {
                    chain.blockChain(blk)
                } catch (e: Throwable) {
                    System.err.println(e.message)
                    inc = 0
                }
                Nmin += inc
            }
        }
        writer.writeLineX(Nmin.toString())             // 7
    }
    writer.writeLineX(n1.toString())                // 8
    return Pair(Nmin,Nmax)
}

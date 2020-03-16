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
                        writer.writeLineX(
                            keys.getPublicKey().getAsHexString() + ' ' +
                            keys.getSecretKey().getAsHexString()
                        )
                    }
                }
            }
            "FC chain join" -> {
                val name= reader.readLineX().nameCheck()
                val type= reader.readLineX()
                val pub =
                    if (type.isEmpty()) {
                        null
                    } else {
                        val oonly= type.toBoolean()
                        val pub= reader.readLineX()
                        ChainPub(oonly, pub)
                    }
                val chain = synchronized (getLock()) {
                    local.joinChain(name,pub)
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
                assert(ln.startsWith("FC chain"))
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
                            val state = reader.readLineX().toState()
                            val heads = chain.getHeads(state)
                            val hs = heads.joinToString(" ")
                            writer.writeLineX(hs)
                            System.err.println("chain heads: $heads")
                        }
                        "FC chain get" -> {
                            val hash = reader.readLineX()
                            val crypt= reader.readLineX()

                            val crypt_= if (crypt == "") null else crypt
                            val blk    = chain.fsLoadBlock(hash, crypt_)
                            val json   = blk.toJson()

                            assert(json.length <= Int.MAX_VALUE)
                            writer.writeBytes(json)
                            //writer.writeLineX("\n")
                            System.err.println("chain get: $hash")
                        }
                        "FC chain reps" -> {
                            val ref = reader.readLineX()

                            val likes =
                                if (ref.hashIsBlock()) {
                                    chain.repsPostSum(ref)
                                } else {
                                    chain.repsAuthor(ref, null)
                                }

                            writer.writeLineX(likes.toString())
                            System.err.println("chain reps: $likes")
                        }

                        "FC chain ban" -> {
                            val hash = reader.readLineX()
                            if (! chain.fsExistsBlock(hash)) {
                                writer.writeLineX("false")
                            } else {
                                chain.blockRejectBan(hash, true)
                                writer.writeLineX("true")
                            }
                        }
                        "FC chain unban" -> {
                            val hash = reader.readLineX()
                            if (chain.fsExistsBlock(hash,"/banned/")) {
                                chain.unban(hash)
                                writer.writeLineX("true")
                            } else {
                                writer.writeLineX("false")
                            }
                        }

                        "FC chain post" -> {
                            val time = reader.readLineX()
                            val sign = reader.readLineX()   // "" / <pvt>
                            val crypt= reader.readLineX()
                            val lkn    = reader.readLineX().toInt()
                            val lkr = reader.readLineX()
                            val code = reader.readLineX()

                            val cods = code.split(' ')
                            val pay  = reader.readLinesX(cods.getOrNull(1) ?: "")

                            val like =
                                if (lkn == 0) {
                                    assert(lkr.isEmpty())
                                    null
                                } else {
                                    assert(lkn%2 == 0) { "like must be even "}
                                    assert(lkr.hashIsBlock()) { "expected block hash" }
                                    Like(lkn, lkr)
                                }

                            val blk = chain.blockNew (
                                Immut (
                                    max (
                                        time.nowToTime(),
                                        chain.heads.map { chain.fsLoadBlock(it, null).immut.time }.max()!!
                                            // TODO: +1 prevents something that happened after to occur simultaneously (also, problem with TODO???)
                                    ),
                                    like,
                                    cods[0],
                                    false,
                                    pay,
                                    emptyArray()
                                ),
                                if (sign.isEmpty()) null else sign,
                                if (crypt.isEmpty()) null else crypt
                            )

                            writer.writeLineX(blk.hash)
                            System.err.println("chain post: ${blk.hash}")

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
    val heads = chain.getHeads(State.PENDING)
    val n1 = heads.size
    writer.writeLineX(n1.toString())                              // 1
    for (head in heads) {
        val pending = ArrayDeque<Hash>()
        pending.push(head)

        // for each head path of blocks
        while (pending.isNotEmpty()) {
            val hash = pending.pop()
            if (visited.contains(hash)) {
                continue
            }
            visited.add(hash)

            val blk = chain.fsLoadBlock(hash, null)

            writer.writeLineX(hash)                            // 2: asks if contains hash
            val state = reader.readLineX().toState()   // 3: receives yes or no
            if (state != State.MISSING) {
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
            val blk = chain.fsLoadBlock(hash, null)
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
                writer.writeLineX(chain.hashState(hash).toString_())   // 3: have or not block
            }
        }

        // receive blocks
        val n2 = reader.readLineX().toInt()    // 5
        Nmax += n2
        var n2_ = 0

        xxx@for (j in 1..n2) {
            try {
                val blk = reader.readLinesX().jsonToBlock() // 6
                //println("recv ${blk.hash}")
                chain.blockChain(blk)
                Nmin++
                n2_++
            } catch (e: Throwable) {
                System.err.println(e.message)
            }
        }
        writer.writeLineX(n2_.toString())             // 7
    }
    writer.writeLineX(n1.toString())                // 8
    return Pair(Nmin,Nmax)
}

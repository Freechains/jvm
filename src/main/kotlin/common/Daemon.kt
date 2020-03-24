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
    private val listenLists = mutableMapOf<String,MutableSet<DataOutputStream>>()
    private val server = ServerSocket(host.port)
    private val local = host

    private fun getLock (chain: Chain? = null) : String {
        return (local.root + (chain?.hash ?: "")).intern()
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
                        //System.err.println(e.stackTrace.contentToString())
                    }
                }
            } catch (e: SocketException) {
                assert(e.message == "Socket closed")
                break
            }
        }
    }

    private fun signal (chain: String, n: Int) {
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

    private fun handle (remote: Socket) {
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
                            keys.publicKey.asHexString + ' ' +
                            keys.secretKey.asHexString
                        )
                    }
                }
            }
            "FC chain join" -> {
                val name= reader.readLineX().nameCheck()
                val trusted= reader.readLineX().toBoolean()
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
                    local.joinChain(name, trusted, pub)
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
                            System.err.println("chain heads: $hs")
                        }
                        "FC chain get" -> {
                            val hash = reader.readLineX()
                            val crypt= reader.readLineX()

                            val crypt2= if (crypt == "") null else crypt
                            val blk    = chain.fsLoadBlock(hash, crypt2)
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
                                    chain.repsPost(ref)
                                } else {
                                    chain.repsAuthor(ref, getNow())
                                }

                            writer.writeLineX(likes.toString())
                            System.err.println("chain reps: $likes")
                        }

                        "FC chain ban" -> {
                            val hash = reader.readLineX()
                            if (! chain.fsExistsBlock(hash)) {
                                writer.writeLineX("false")
                            } else {
                                //chain.blockBan(hash)
                                writer.writeLineX("true")
                            }
                        }
                        "FC chain unban" -> {
                            val hash = reader.readLineX()
                            if (chain.fsExistsBlock(hash,"/bans/")) {
                                //chain.blockUnban(hash)
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
                                    assert(lkn==-1 || lkn==1) { "invalid like"}
                                    assert(lkr.hashIsBlock()) { "expected block hash" }
                                    Like(lkn, lkr)
                                }

                            var ret: String
                            try {
                                val blk = chain.blockNew (
                                    Immut (
                                        max (
                                            time.nowToTime(),
                                            1 + chain.getHeads(State.ACCEPTED).map { chain.fsLoadBlock(it, null).immut.time }.max()!!
                                        ),
                                        cods[0],
                                        false,
                                        pay,
                                        null,
                                        like,
                                        emptyArray()
                                    ),
                                    if (sign.isEmpty()) null else sign,
                                    if (crypt.isEmpty()) null else crypt
                                )
                                ret = blk.hash
                            } catch (e: Throwable) {
                                //System.err.println(e.stackTrace.contentToString())
                                ret = e.message!!
                            }
                            writer.writeLineX(ret)
                            System.err.println("chain post: $ret")

                            thread {
                                signal(name,1)
                            }
                        }
                        "FC chain send" -> {
                            val host2 = reader.readLineX()

                            val (host,port) = host2.hostSplit()

                            val socket = Socket(host, port)
                            val (nmin,nmax) = socket.chainSend(chain)
                            System.err.println("chain send: $name ($nmin/$nmax)")
                            writer.writeLineX("$nmin / $nmax")
                        }
                        "FC chain recv" -> {
                            val (nmin,nmax) = remote.chainRecv(chain)
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

fun Socket.chainSend (chain: Chain) : Pair<Int,Int> {
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
    var nmin    = 0
    var nmax    = 0

    // for each local head
    val heads = chain.getHeads(State.PENDING)
    val nout = heads.size
    writer.writeLineX(nout.toString())                              // 1
    for (head in heads) {
        val pending = ArrayDeque<Hash>()
        pending.push(head)

        val toSend = mutableSetOf<Hash>()

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
            //println("[add] $hash")
            toSend.add(hash)
            for (back in blk.immut.backs) {
                pending.push(back)
            }
        }

        writer.writeLineX("")                     // 4: will start sending nodes
        writer.writeLineX(toSend.size.toString())    // 5: how many
        val nin = toSend.size
        val sorted = toSend.sortedWith(compareBy{it.toHeight()})
        for (hash in sorted) {
            val blk = chain.fsLoadBlock(hash, null)
            //println("[send] $hash")
            writer.writeBytes(blk.toJson())          // 6
            writer.writeLineX("\n")
        }
        val nin2 = reader.readLineX().toInt()    // 7: how many blocks again
        assert(nin >= nin2)
        nmin += nin2
        nmax += nin
    }
    val nout2 = reader.readLineX().toInt()        // 8: how many heads again
    assert(nout == nout2)

    return Pair(nmin,nmax)
}

fun Socket.chainRecv (chain: Chain) : Pair<Int,Int> {
    val reader = DataInputStream(this.getInputStream()!!)
    val writer = DataOutputStream(this.getOutputStream()!!)

    // - sends most recent timestamp
    // - answers if contains each node
    // - receives all

    var nmax = 0
    var nmin = 0

    // for each remote head
    val nout = reader.readLineX().toInt()        // 1
    for (i in 1..nout) {
        // for each head path of blocks
        while (true) {
            val hash = reader.readLineX()   // 2: receives hash in the path
            //println("[recv-1] $hash")
            if (hash.isEmpty()) {                   // 4
                break                               // nothing else to answer
            } else {
                writer.writeLineX(chain.hashState(hash).toString_())   // 3: have or not block
            }
        }

        // receive blocks
        val nin = reader.readLineX().toInt()    // 5
        nmax += nin
        var nin2 = 0

        xxx@for (j in 1..nin) {
            try {
                val blk = reader.readLinesX().jsonToBlock() // 6
                //println("[recv-2] ${blk.hash}")
                chain.blockChain(blk)
                nmin++
                nin2++
            } catch (e: Throwable) {
                System.err.println(e.message)
                //System.err.println(e.stackTrace.contentToString())
            }
        }
        writer.writeLineX(nin2.toString())             // 7
    }
    writer.writeLineX(nout.toString())                // 8
    return Pair(nmin,nmax)
}

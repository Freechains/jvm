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
        val (v1,v2,v3,cmd) =
            Regex("FC v(\\d+)\\.(\\d+)\\.(\\d+) (.*)").find(ln)!!.destructured
        assert(MAJOR==v1.toInt() && MINOR>=v2.toInt()) { "incompatible versions" }
        when (cmd) {
            "host stop" -> {
                writer.writeLineX("true")
                server.close()
                System.err.println("host stop: $local")
            }
            "host now" -> {
                val now= reader.readLineX().toLong()
                setNow(now)
                writer.writeLineX("true")
                System.err.println("host now: $now")
            }
            "crypto create" -> {
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
            "chain join" -> {
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
            "chain listen" -> {
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
                assert(cmd.startsWith("chain"))
                val name  = reader.readLineX().nameCheck()
                val chain = synchronized (getLock()) {
                    local.loadChain(name)
                }
                synchronized (getLock(chain)) {
                    when (cmd) {
                        "chain genesis" -> {
                            val hash  = chain.getGenesis()
                            writer.writeLineX(hash)
                            System.err.println("chain genesis: $hash")
                        }
                        "chain heads" -> {
                            val state = reader.readLineX().toState()
                            val heads = chain.getHeads(state)
                            val hs = heads.joinToString(" ")
                            writer.writeLineX(hs)
                            System.err.println("chain heads: $hs")
                        }
                        "chain get" -> {
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
                        "chain reps" -> {
                            val ref = reader.readLineX()

                            val likes =
                                if (ref.hashIsBlock()) {
                                    val (pos,neg) = chain.repsPost(ref)
                                    pos - neg
                                } else {
                                    chain.repsAuthor(ref, getNow(), chain.getHeads(State.ALL))
                                }

                            writer.writeLineX(likes.toString())
                            System.err.println("chain reps: $likes")
                        }

                        "chain post" -> {
                            val sign = reader.readLineX()   // "" / <pvt>
                            val crypt= reader.readLineX()
                            val lkn     = reader.readLineX().toInt()
                            val lkr  = reader.readLineX()
                            val len     = reader.readLineX().toInt()
                            var pay  = reader.readLinesX()
                            while (pay.length < len) {
                                pay += "\n"
                                pay += reader.readLinesX()
                            }
                            assert(pay.length <= S128_pay) { "post is too large" }

                            val like =
                                if (lkn == 0) {
                                    assert(lkr.isEmpty())
                                    null
                                } else {
                                    assert(lkn==-1 || lkn==1) { "invalid like" }
                                    assert(lkr.hashIsBlock()) { "expected block hash" }
                                    Like(lkn, lkr)
                                }

                            var ret: String
                            try {
                                val blk = chain.blockNew (
                                    Immut (
                                        0,
                                        Payload(false, ""),
                                        null,
                                        like,
                                        emptyArray()
                                    ),
                                    pay,
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
                        "chain send" -> {
                            val host2 = reader.readLineX()
                            val (host,port) = host2.hostSplit()
                            val socket = Socket(host, port)
                            val r = DataInputStream(socket.getInputStream()!!)
                            val w = DataOutputStream(socket.getOutputStream()!!)
                            w.writeLineX("$PRE chain _recv_")
                            w.writeLineX(chain.name)
                            val (nmin,nmax) = chainSend(r, w, chain)
                            System.err.println("chain send: $name ($nmin/$nmax)")
                            writer.writeLineX("$nmin / $nmax")
                        }
                        "chain recv" -> {
                            val host2 = reader.readLineX()
                            val (host,port) = host2.hostSplit()
                            val socket = Socket(host, port)
                            val r = DataInputStream(socket.getInputStream()!!)
                            val w = DataOutputStream(socket.getOutputStream()!!)
                            w.writeLineX("$PRE chain _send_")
                            w.writeLineX(chain.name)
                            val (nmin,nmax) = chainRecv(r, w, chain)
                            System.err.println("chain recv: $name ($nmin/$nmax)")
                            writer.writeLineX("$nmin / $nmax")
                        }
                        "chain _send_" -> {
                            val r = DataInputStream(remote.getInputStream()!!)
                            val w = DataOutputStream(remote.getOutputStream()!!)
                            val (nmin,nmax) = chainSend(r, w, chain)
                            System.err.println("chain send: $name: ($nmin/$nmax)")
                            thread {
                                signal(name, nmin)
                            }
                            //writer.writeLineX(ret)
                        }
                        "chain _recv_" -> {
                            val r = DataInputStream(remote.getInputStream()!!)
                            val w = DataOutputStream(remote.getOutputStream()!!)
                            val (nmin,nmax) = chainRecv(r, w, chain)
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

fun chainSend (reader: DataInputStream, writer: DataOutputStream, chain: Chain) : Pair<Int,Int> {
    // - receives most recent timestamp
    // - DFS in heads
    //   - asks if contains hash
    //   - aborts path if reaches timestamp+24h
    //   - pushes into toSend
    // - sends toSend

    val visited = HashSet<Hash>()
    var nmin    = 0
    var nmax    = 0

    // for each local head
    val heads = chain.getHeads(State.LINKED)
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
            val blk1 = chain.fsLoadBlock(hash, null)
            blk1.fronts.clear()
            val blk2 = when (chain.blockState(blk1, getNow())) {
                State.HIDDEN -> blk1.copy(pay = "")   // don't send actual payload if hidden
                else           -> blk1
            }
            //println("[send] $hash")
            writer.writeBytes(blk2.toJson())          // 6
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

fun chainRecv (reader: DataInputStream, writer: DataOutputStream, chain: Chain) : Pair<Int,Int> {
    // - sends most recent timestamp
    // - answers if contains each node
    // - receives all

    var nmax = 0
    var nmin = 0

    // list of received hidden blocks (empty payloads)
    // will check if are really hidden
    val hiddens = mutableListOf<Block>()

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
                writer.writeLineX(chain.hashState(hash, getNow()).toString_())   // 3: have or not block
            }
        }

        // receive blocks
        val nin = reader.readLineX().toInt()    // 5
        nmax += nin
        var nin2 = 0

        xxx@for (j in 1..nin) {
            try {
                val blk = reader.readLinesX().jsonToBlock() // 6
                assert(blk.pay.length <= S128_pay) { "post is too large" }
                assert(chain.getHeads(State.BLOCKED).size <= N16_blockeds) { "too many blocked blocks" }

                //println("[recv] ${blk.hash}")
                chain.blockChain(blk)
                if (blk.pay == "") {
                    if (blk.immut.pay.hash == "".calcHash()) {
                        // payload is really an empty string
                    } else {
                        hiddens.add(blk)
                    }
                }
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

    for (blk in hiddens) {
        assert(chain.blockState(blk, getNow()) == State.HIDDEN)
    }

    return Pair(nmin,nmax)
}

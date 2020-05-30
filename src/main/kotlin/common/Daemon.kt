package org.freechains.common
import org.freechains.platform.readNBytesX

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
                remote.soTimeout = 5000
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
        val has1 = synchronized (listenLists) { listenLists.containsKey(chain) }
        if (has1) {
            val wrs = synchronized (listenLists) { listenLists[chain]!!.toList() }
            for (wr in wrs) {
                try {
                    wr.writeLineX(n.toString())
                } catch (e: Throwable) {
                    synchronized (listenLists) { listenLists[chain]!!.remove(wr) }
                }
            }
        }
        val has2 = synchronized (listenLists) { listenLists.containsKey("*") }
        if (has2) {
            val wrs = synchronized (listenLists) { listenLists["*"]!!.toList() }
            for (wr in wrs) {
                try {
                    wr.writeLineX(n.toString() + " " + chain)
                } catch (e: Throwable) {
                    synchronized (listenLists) { listenLists["*"]!!.remove(wr) }
                }
            }
        }
    }

    private fun handle (remote: Socket) {
        val reader = DataInputStream(remote.getInputStream()!!)
        val writer = DataOutputStream(remote.getOutputStream()!!)
        val ln = reader.readLineX()
        val (v1,v2,_,cmd) =
            Regex("FC v(\\d+)\\.(\\d+)\\.(\\d+) (.*)").find(ln)!!.destructured
        assert(MAJOR==v1.toInt() && MINOR>=v2.toInt()) { "incompatible versions" }

        //println("addr = ${remote.inetAddress!!}")
        if (!remote.inetAddress!!.toString().equals("/127.0.0.1")) {
            //println("no = ${remote.inetAddress!!}")
            assert(cmd.equals("peer _send_") || cmd.equals("peer _recv_")) { "invalid remote address" }
            //println("ok = ${remote.inetAddress!!}")
        }

        //println("[handle] $cmd")
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
            "peer ping" -> {
                writer.writeLineX("true")
                System.err.println("peer ping")
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

            "chains join" -> {
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
                    local.chainsJoin(name, trusted, pub)
                }
                writer.writeLineX(chain.hash)
                System.err.println("chains join: $name (${chain.hash})")
            }
            "chains leave" -> {
                val name= reader.readLineX().nameCheck()
                val ret = local.chainsLeave(name)
                writer.writeLineX(ret.toString())
                System.err.println("chains leave: $name -> $ret")
            }
            "chains list" -> {
                val ret = local.chainsList().joinToString(" ")
                writer.writeLineX(ret.toString())
                System.err.println("chains list: $ret")
            }
            "chains listen" -> {
                remote.soTimeout = 0
                synchronized (listenLists) {
                    if (! listenLists.containsKey("*")) {
                        listenLists["*"] = mutableSetOf()
                    }
                    listenLists["*"]!!.add(writer)
                }
            }

            "chain listen" -> {
                remote.soTimeout = 0
                val name= reader.readLineX().nameCheck()
                synchronized (listenLists) {
                    if (! listenLists.containsKey(name)) {
                        listenLists[name] = mutableSetOf()
                    }
                    listenLists[name]!!.add(writer)
                }
            }
            else -> {
                assert(cmd.startsWith("chain") || cmd.startsWith("peer"))
                val name  = reader.readLineX().nameCheck()
                val chain = synchronized (getLock()) {
                    local.chainsLoad(name)
                }
                synchronized (getLock(chain)) {
                    when (cmd) {

                        // CHAIN

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
                        "chain traverse" -> {
                            val state = reader.readLineX().toState()
                            val hashes = chain.getHeads(state)
                            val downto = reader.readLineX().split(" ")
                            //println("H=$heads // D=$downto")
                            val all = chain
                                .bfsBacks(hashes,false) {
                                    //println("TRY ${it.hash} -> ${downto.contains(it.hash)}")
                                    !downto.contains(it.hash)
                                }
                                .map {it.hash}
                                .reversed()
                            //println("H=$heads // D=$downto")
                            val ret = all.joinToString(" ")
                            writer.writeLineX(ret)
                            System.err.println("chain traverse: $ret")
                        }
                        "chain get" -> {
                            val b_p  = reader.readLineX()
                            val hash = reader.readLineX()
                            val crypt= reader.readLineX()

                            val ret = when (b_p) {
                                "block"   -> chain.fsLoadBlock(hash).toJson()
                                "payload" -> chain.fsLoadPay(hash, if (crypt == "") null else crypt)
                                else -> error("impossible case")
                            }

                            writer.writeLineX(ret.length.toString())
                            writer.writeBytes(ret)
                            //writer.writeLineX("\n")
                            System.err.println("chain get: $hash")
                        }
                        "chain remove" -> {
                            val hash = reader.readLineX()
                            chain.blockRemove(hash)
                            writer.writeLineX("true")
                            System.err.println("chain remove: $hash")
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
                            val pay  = reader.readNBytesX(len).toString(Charsets.UTF_8)
                            reader.readLineX()
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

                        // PEER

                        "peer send" -> {
                            val host2 = reader.readLineX()
                            val (host,port) = host2.hostSplit()
                            val socket = Socket_5s(host, port)
                            val r = DataInputStream(socket.getInputStream()!!)
                            val w = DataOutputStream(socket.getOutputStream()!!)
                            w.writeLineX("$PRE peer _recv_")
                            w.writeLineX(chain.name)
                            val (nmin,nmax) = peerSend(r, w, chain)
                            System.err.println("peer send: $name: ($nmin/$nmax)")
                            writer.writeLineX("$nmin / $nmax")
                        }
                        "peer recv" -> {
                            val host2 = reader.readLineX()
                            val (host,port) = host2.hostSplit()
                            val socket = Socket_5s(host, port)
                            val r = DataInputStream(socket.getInputStream()!!)
                            val w = DataOutputStream(socket.getOutputStream()!!)
                            w.writeLineX("$PRE peer _send_")
                            w.writeLineX(chain.name)
                            val (nmin,nmax) = peerRecv(r, w, chain)
                            System.err.println("peer recv: $name: ($nmin/$nmax)")
                            writer.writeLineX("$nmin / $nmax")
                        }
                        "peer _send_" -> {
                            val r = DataInputStream(remote.getInputStream()!!)
                            val w = DataOutputStream(remote.getOutputStream()!!)
                            val (nmin,nmax) = peerSend(r, w, chain)
                            System.err.println("peer _send_: $name: ($nmin/$nmax)")
                            thread {
                                signal(name, nmin)
                            }
                            //writer.writeLineX(ret)
                        }
                        "peer _recv_" -> {
                            val r = DataInputStream(remote.getInputStream()!!)
                            val w = DataOutputStream(remote.getOutputStream()!!)
                            val (nmin,nmax) = peerRecv(r, w, chain)
                            System.err.println("peer _recv_: $name: ($nmin/$nmax)")
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
    }
}

fun peerSend (reader: DataInputStream, writer: DataOutputStream, chain: Chain) : Pair<Int,Int> {
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
    val heads = chain.getHeads(State.ALL)
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

            val blk = chain.fsLoadBlock(hash)

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
            val out = chain.fsLoadBlock(hash)
            out.fronts.clear()
            val json = out.toJson()
            writer.writeLineX(json.length.toString()) // 6
            writer.writeBytes(json)
            val pay = when (chain.blockState(out, getNow())) {
                State.HIDDEN -> ""
                else         -> chain.fsLoadPay(hash,null)
            }
            writer.writeLineX(pay.length.toString())
            writer.writeBytes(pay)
            writer.writeLineX("")
        }
        val nin2 = reader.readLineX().toInt()    // 7: how many blocks again
        assert(nin >= nin2)
        nmin += nin2
        nmax += nin
    }
    val nout2 = reader.readLineX().toInt()       // 8: how many heads again
    assert(nout == nout2)

    return Pair(nmin,nmax)
}

fun peerRecv (reader: DataInputStream, writer: DataOutputStream, chain: Chain) : Pair<Int,Int> {
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
                val len1 = reader.readLineX().toInt() // 6
                val blk = reader.readNBytesX(len1).toString(Charsets.UTF_8).jsonToBlock()
                val len2 = reader.readLineX().toInt()
                assert(len2 <= S128_pay) { "post is too large" }
                val pay = reader.readNBytesX(len2).toString(Charsets.UTF_8)
                reader.readLineX()
                assert(chain.getHeads(State.BLOCKED).size <= N16_blockeds) { "too many blocked blocks" }

                //println("[recv] ${blk.hash} // len=$len2 // ${pay.length}")
                chain.blockChain(blk,pay)
                if (pay=="" && blk.immut.pay.hash!="".calcHash()) {
                    hiddens.add(blk)
                } // else: payload is really an empty string

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
        assert(chain.blockState(blk, getNow()) == State.HIDDEN) { "bug found: expected hidden state"}
    }

    return Pair(nmin,nmax)
}

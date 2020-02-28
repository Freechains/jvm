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
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class Daemon (host : Host) {
    val waitList : WaitLists = HashMap()
    val listenLists = mutableMapOf<String,MutableSet<DataOutputStream>>()
    val server = ServerSocket(host.port)
    val local = host

    fun getLock (chain: Chain? = null) : String {
        return local.root.intern() + (if (chain == null) "" else chain.hash.intern())
    }

    fun daemon () {
        //System.err.println("host start: $host")
        thread {
            while (true) {
                Thread.sleep(30 * min)
                f_waitLists()
            }
        }
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

    fun f_waitLists () {
        val chains = synchronized (waitList) { waitList.keys.toList() }
        for (chain in chains) {
            synchronized (getLock(chain)) {
                val waitList = waitList[chain]!!
                val nxt = waitList.rem(getNow())
                if (nxt != null) {
                    chain.blockChain(nxt)
                }
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
                NOW = Instant.now().toEpochMilli() - now
                this.f_waitLists()
                writer.writeLineX("true")
                System.err.println("host now: $now")
            }
            "FC host flush" -> {
                this.f_waitLists()
                writer.writeLineX("true")
                System.err.println("host flush")
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
                val name    = reader.readLineX().nameCheck()
                val ro    = reader.readLineX() == "ro"
                val shared  = reader.readLineX()
                val public  = reader.readLineX()
                val private = reader.readLineX()
                val chain = synchronized (getLock()) {
                    local.joinChain(name,ro,arrayOf(shared,public,private))
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
                            val hash = reader.readLineX()

                            val blk   = chain.loadBlockFromHash(hash,true)
                            val json  = blk.toJson()

                            assert(json.length <= Int.MAX_VALUE)
                            writer.writeBytes(json)
                            //writer.writeLineX("\n")
                            System.err.println("chain get: $hash")
                        }
                        "FC chain reps" -> {
                            val time = reader.readLineX()
                            val pub = reader.readLineX()

                            val likes = chain.getRep(pub, time.nowToTime())

                            writer.writeLineX(likes.toString())
                            System.err.println("chain reps: $likes")
                        }
                        "FC chain post" -> {
                            val time = reader.readLineX()
                            val like   = reader.readLineX().toInt()
                            val cod  = reader.readLineX()
                            val cry = reader.readLineX().toBoolean()

                            val cods = cod.split(' ')
                            val pay  = reader.readLinesX(cods.getOrNull(1) ?: "")

                            val refs = reader.readLinesX()
                            val sig  = reader.readLineX()

                            val refs_ = if (refs == "") emptyArray() else refs.split('\n').toTypedArray()
                            val likes =
                                if (refs_.isEmpty()) {
                                    arrayOf<Like?>(null)
                                } else {
                                    if (chain.containsBlock(refs_[0])) {
                                        // refs a post
                                        val blk = chain.loadBlockFromHash(refs_[0], false)
                                        val l1 = arrayOf(Like(like/2, LikeType.POST, refs_[0]))
                                        if (blk.signature == null)
                                            l1
                                        else
                                            l1.plus(Like(like/2, LikeType.PUBKEY, blk.signature.pubkey))
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
                                    sig,    // TODO: check sig or chain, return error
                                    BlockHashable (
                                        max (
                                            time.nowToTime(),
                                            chain.heads.map { chain.loadBlockFromHash(it,false).hashable.time }.max()!!
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
                            val (nmin,nmax) = remote.chain_recv(chain,waitList)
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
            visited.add(hash)
            //println("[send] $hash")

            val blk = chain.loadBlockFromHash(hash,false)

            writer.writeLineX(hash)                               // 2: asks if contains hash
            val has = reader.readLineX().toBoolean()    // 3: receives yes or no
            if (has) {
                //println("[send] has: $hash")
                continue                             // already has: finishes subpath
            }

            // sends this one and visits children
            toSend.push(hash)
            //println("[send] backs: ${blk.hashable.backs.size}")
            for (back in blk.hashable.backs) {
                if (! visited.contains(back)) {
                    //println("[send] back: $back")
                    pending.push(back)
                }
            }
        }

        //println("[send]")
        writer.writeLineX("")                     // 4: will start sending nodes
        //println("[send] ${toSend.size.toString()}")
        writer.writeLineX(toSend.size.toString())    // 5: how many
        val n2 = toSend.size
        while (toSend.isNotEmpty()) {
            val hash = toSend.pop()
            val blk = chain.loadBlockFromHash(hash,false)
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

fun Socket.chain_recv (chain: Chain, waitLists: WaitLists) : Pair<Int,Int> {
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
                val has = chain.containsBlock(hash)
                writer.writeLineX(has.toString())   // 3: have or not block
            }
        }

        // receive blocks
        val n2 = reader.readLineX().toInt()    // 5
        Nmax += n2
        //println("[recv] $n2")
        xxx@for (j in 1..n2) {
            val blk = reader.readLinesX().jsonToBlock() // 6
            //println("[recv] ${blk.hash}")

            //println("${blk.hash} / ${blk.height} / ${blk.hashable.time}")
            when {
                // refuse block from the future
                (blk.hashable.time > now+T30M_future) ->
                    continue@xxx

                // refuse blocks too old
                (blk.hashable.time < now-T120_past) ->
                    continue@xxx

                // enqueue noob/late block
                (
                    !chain.fromOwner(blk)                           &&  // owner sig always pass
                    blk.hashable.like == null                       &&  // likes always pass
                    blk.height > 1                                  &&  // first block always pass
                    (
                        blk.hashable.time <= now-T2H_past           ||  // late
                        blk.signature == null                       ||  // no sig
                        chain.getRep(blk.signature.pubkey,now) <= 0     // no rep
                    )
                ) -> {
                    if (chain.backsCheck(blk)) {
                        val waitList = synchronized (waitLists) {
                            waitLists.createGet(chain)
                        }
                        waitList.add(blk,now)
                    } //else {
                        // not all backs exist (probably because noob/late as well)
                        // ok: do not inserted in waitList
                    //}
                    continue@xxx
                }
            }

            var inc = 1
            try {
                chain.blockChain(blk)
            } catch (e: Throwable) {
                System.err.println(e.message)
                inc = 0
            }
            Nmin += inc
        }
        writer.writeLineX(Nmin.toString())             // 7
    }
    writer.writeLineX(n1.toString())                // 8
    return Pair(Nmin,Nmax)
}

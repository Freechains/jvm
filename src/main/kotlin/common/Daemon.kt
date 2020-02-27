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
import java.io.FileNotFoundException
import java.lang.Long.max
import java.time.Instant
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class Daemon (host : Host) {
    val pastLists : WaitLists = HashMap()
    //val noobLists : WaitLists = HashMap()
    val listenLists = mutableMapOf<String,MutableSet<DataOutputStream>>()
    val server = ServerSocket(host.port)
    val local = host

    fun getLock (chain: Chain? = null) : String {
        return local.root.intern() + (if (chain == null) "" else chain.hash.intern())
    }

    fun daemon () {
        //System.err.println("host start: $host")
        thread {
            f_waitLists()
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
        while (true) {
            Thread.sleep(30 * min)
            val chains = synchronized (pastLists) { pastLists.keys.toList() }
            for (chain in chains) {
                synchronized (getLock(chain)) {
                    val pastList = pastLists[chain]!!
                    val nxt = pastList.rem(getNow())
                    if (nxt != null) {
                        chain.blockChain(nxt)
                    }
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

                            val likes = chain.repPubkey(time.nowToTime(), pub)

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
                            val like_ =
                                if (refs_.isEmpty()) {
                                    null
                                } else {
                                    if (chain.containsBlock(refs_[0])) {
                                        // refs a post
                                        val blk = chain.loadBlockFromHash(refs_[0], false)
                                        Like(like/2, LikeType.POST, refs_[0])
                                        Like(like/2, LikeType.POST, blk.signature!!.pubkey)
                                    } else {
                                        // refs a pubkey
                                        Like(like, LikeType.PUBKEY, refs_[0])
                                    }
                                }

                            val blk = chain.blockNew (
                                sig,
                                BlockHashable (
                                    max (
                                        time.nowToTime(),
                                        chain.heads.map { chain.loadBlockFromHash(it,false).hashable.time }.max()!!
                                    ),
                                    like_,
                                    cods[0],
                                    cry,
                                    pay,
                                    refs_,
                                    emptyArray()
                                )
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
                            val n = socket.chain_send(chain)
                            System.err.println("chain send: $name: $n")
                            writer.writeLineX(n.toString())
                        }
                        "FC chain recv" -> {
                            val n = remote.chain_recv(chain,pastLists)
                            System.err.println("chain recv: $name: $n")
                            thread {
                                signal(name, n)
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

fun Socket.chain_send (chain: Chain) : Int {
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
    var N       = 0
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
        assert(n2 == n2_)
        N += n2
    }
    val n1_ = reader.readLineX().toInt()        // 8: how many heads again
    assert(n1 == n1_)

    return N
}

fun Socket.chain_recv (chain: Chain, pastLists: WaitLists) : Int {
    val reader = DataInputStream(this.getInputStream()!!)
    val writer = DataOutputStream(this.getOutputStream()!!)

    // - sends most recent timestamp
    // - answers if contains each node
    // - receives all

    var N = 0
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
        //println("[recv] $n2")
        N += n2
        for (j in 1..n2) {
            val blk = reader.readLinesX().jsonToBlock() // 6
            //println("[recv] ${blk.hash}")

            if (blk.hashable.time >= now+T30M_future) {
                continue    // refuse block from the future
            }

            // post is too old:
            // insert into waiting list to "blockChain()" it later
            if (blk.hashable.time <= now-T2H_sync) {
                try {
                    chain.blockAssert(blk)  // might fail if back also failed
                    val pastList = synchronized (pastLists) {
                        pastLists.createGet(chain, CMP_past)
                    }
                    pastList.add(blk,now)
                } catch (e: FileNotFoundException) {
                    // ok: not inserted in waitList
                }

                continue
            }

            chain.blockChain(blk)
        }
        writer.writeLineX(n2.toString())            // 7
    }
    writer.writeLineX(n1.toString())                // 8
    return N
}

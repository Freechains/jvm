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

val h24 = 1000 * 60 * 60 * 24


fun daemon (host : Host) {
    val socket = ServerSocket(host.port)
    //System.err.println("host start: $host")

    while (true) {
        try {
            val remote = socket.accept()
            System.err.println("remote connect: $host <- ${remote.inetAddress.hostAddress}")
            thread {
                try {
                    handle(socket, remote, host)
                } catch (e: Throwable) {
                    remote.close()
                }
            }
        } catch (e: SocketException) {
            assert(e.message == "Socket closed")
            break
        }
    }
}

fun handle (server: ServerSocket, remote: Socket, local: Host) {
    val reader = DataInputStream(remote.getInputStream()!!)
    val writer = DataOutputStream(remote.getOutputStream()!!)

    val ln = reader.readLineX()
    when (ln) {
        "FC host stop" -> {
            writer.writeLineX("true")
            server.close()
            System.err.println("host stop: $local")
        }
        "FC chain create" -> {
            val path    = reader.readLineX()
            val shared  = reader.readLineX()
            val public  = reader.readLineX()
            val private = reader.readLineX()
            val chain = local.createChain(path,arrayOf(shared,public,private))
            writer.writeLineX(chain.hash)
            System.err.println("chain create: $path (${chain.hash})")
        }
        "FC chain genesis" -> {
            val path  = reader.readLineX().nameCheck()
            val chain = local.loadChain(path)
            val hash  = chain.toGenHash()
            writer.writeLineX(hash)
            System.err.println("chain genesis: $hash")
        }
        "FC chain heads" -> {
            val path  = reader.readLineX().nameCheck()
            val chain = local.loadChain(path)
            for (head in chain.heads) {
                writer.writeLineX(head)
            }
            writer.writeLineX("")
            System.err.println("chain heads: ${chain.heads}")
        }
        "FC chain get" -> {
            val path = reader.readLineX().nameCheck()
            val hash = reader.readLineX()

            val chain = local.loadChain(path)
            val blk   = chain.loadBlockFromHash(hash,true)
            val json  = blk.toJson()

            assert(json.length <= Int.MAX_VALUE)
            writer.writeBytes(json)
            writer.writeLineX("\n")
            System.err.println("chain get: $hash")
        }
        "FC chain put" -> {
            val path = reader.readLineX().nameCheck()
            val cod  = reader.readLineX()
            val time = reader.readLineX()
            val cry  = reader.readLineX().toBoolean()
            val pay  = reader.readLinesX()

            val chain = local.loadChain(path)
            val blk = if (time == "now") chain.publish(cod,cry,pay) else chain.publish(cod,cry,pay,time.toLong())

            writer.writeLineX(blk.hash)
            System.err.println("chain put: ${blk.hash}")
        }
        "FC chain send" -> {
            val path = reader.readLineX().nameCheck()
            val host_ = reader.readLineX()

            val chain = local.loadChain(path)
            val (host,port) = host_.hostSplit()

            val socket = Socket(host, port)
            val n = socket.chain_send(chain)
            System.err.println("chain send: $path: $n")
            writer.writeLineX(n.toString())
        }
        "FC chain recv" -> {
            val path = reader.readLineX().nameCheck()
            val chain = local.loadChain(path)
            val n = remote.chain_recv(chain)
            System.err.println("chain recv: $path: $n")
            //writer.writeLineX(ret)
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
        else -> { error("$ln: invalid header type") }
    }
    remote.close()
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

    val maxTime = reader.readLineX().toLong()
    val visited = HashSet<Hash>()
    val toSend  = ArrayDeque<Hash>()
    var N       = 0
    //println("[send] $maxTime")

    // for each local head
    val n1 = chain.heads.size
    writer.writeLineX(n1.toString())
    for (head in chain.heads) {
        val pending = ArrayDeque<Hash>()
        pending.push(head)

        // for each head path of blocks
        while (pending.isNotEmpty()) {
            val hash = pending.pop()
            visited.add(hash)
            //println("[send] $hash")
            writer.writeLineX(hash)                  // asks if contains hash
            val has = reader.readLineX().toBoolean() // receives yes or no
            if (has) {
                //println("[send] has: $hash")
                continue                             // already has: finishes subpath
            }
            val blk = chain.loadBlockFromHash(hash)
            if (maxTime-h24 > blk.hashable.time) {
                //println("[send] max: $hash")
                toSend.clear()                       // no, but too old: aborts this head path entirely
                break
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
        writer.writeLineX("")                     // will start sending nodes
        //println("[send] ${toSend.size.toString()}")
        writer.writeLineX(toSend.size.toString())    // how many
        val n2 = toSend.size
        while (toSend.isNotEmpty()) {
            val hash = toSend.pop()
            val old = chain.loadBlockFromHash(hash)
            val new = old.copy(fronts=emptyArray())  // remove fronts
            //println("[send] ${new.hash}")
            writer.writeBytes(new.toJson())
            writer.writeLineX("\n")
        }
        val n2_ = reader.readLineX().toInt()          // how many blocks again
        assert(n2 == n2_)
        N += n2
    }
    val n1_ = reader.readLineX().toInt()             // how many heads again
    assert(n1 == n1_)

    return N
}

fun Socket.chain_recv (chain: Chain) : Int {
    val reader = DataInputStream(this.getInputStream()!!)
    val writer = DataOutputStream(this.getOutputStream()!!)

    fun getMaxTime () : Long {
        var max: Long = 0
        for (head in chain.heads) {
            val blk = chain.loadBlockFromHash(head)
            if (blk.hashable.time > max) {
                max = blk.hashable.time
            }
        }
        return max
    }

    // - sends most recent timestamp
    // - answers if contains each node
    // - receives all

    val maxTime = getMaxTime()
    writer.writeLineX(maxTime.toString())
    //println("[recv] $maxTime")
    var N = 0

    // for each remote head
    val n1 = reader.readLineX().toInt()
    for (i in 1..n1) {
        // for each head path of blocks
        while (true) {
            val hash = reader.readLineX()           // receives hash in the path
            //println("[recv] $hash")
            if (hash == "") {
                break                               // nothing else to answer
            } else {
                val has = chain.containsBlock(hash)
                writer.writeLineX(has.toString())   // have or not block
            }
        }

        // receive blocks
        val n2 = reader.readLineX().toInt()
        //println("[recv] $n2")
        N += n2
        for (j in 1..n2) {
            val blk = reader.readLinesX().jsonToBlock()
            //println("[recv] ${blk.hash}")
            assert(maxTime-h24 <= blk.hashable.time)
            chain.assertBlock(blk)
            chain.reheads(blk)
            chain.saveBlock(blk)
            chain.save()
        }
        writer.writeLineX(n2.toString())
    }
    writer.writeLineX(n1.toString())
    return N
}

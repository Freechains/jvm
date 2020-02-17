package org.freechains.common

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

import com.goterl.lazycode.lazysodium.LazySodium
import com.goterl.lazycode.lazysodium.interfaces.PwHash
import org.freechains.platform.lazySodium

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
            writer.writeLineX("1")
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
            val blk   = chain.loadBlockFromHash(hash)
            val json  = blk.toJson()

            assert(json.length <= Int.MAX_VALUE)
            writer.writeBytes(json)
            writer.writeLineX("\n")
            System.err.println("chain get: $hash")
        }
        "FC chain put" -> {
            val path = reader.readLineX().nameCheck()
            val enc = reader.readLineX()
            val pay = reader.readLinesX()

            val chain = local.loadChain(path)
            val blk = if (local.timestamp) chain.publish(enc,pay) else chain.publish(enc,pay,0)

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
                    writer.writeLineX(LazySodium.toHex(pwh))
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

    writer.writeLineX("FC chain recv")
    writer.writeLineX(chain.name)

    val toSend = mutableSetOf<Hash>()
    fun traverse (hash: Hash) {
        if (toSend.contains(hash)) {
            return
        } else {
            toSend.add(hash)
            val blk = chain.loadBlockFromHash(hash)
            for (front in blk.fronts) {
                traverse(front)
            }
        }
    }

    while (true) {
        val head = reader.readLineX()
        if (head == "") {
            break
        }
        if (chain.containsBlock(head)) {
            val blk = chain.loadBlockFromHash(head)
            for (front in blk.fronts) {
                traverse(front)
            }
        }
    }

    writer.writeLineX(toSend.size.toString())
    val sorted = toSend.toSortedSet(compareBy({it.length},{it}))
    for (hash in sorted) {
        val old = chain.loadBlockFromHash(hash)
        // remove fronts
        val new = Block(old.hashable,emptyArray(),old.signature,old.hash)
        writer.writeBytes(new.toJson())
        writer.writeLineX("\n")
    }

    reader.readLineX()
    return toSend.size
}

fun Socket.chain_recv (chain: Chain) : Int {
    val reader = DataInputStream(this.getInputStream()!!)
    val writer = DataOutputStream(this.getOutputStream()!!)

    // transmit heads
    for (head in chain.heads) {
        writer.writeLineX(head)
    }
    writer.writeLineX("")

    val n = reader.readLineX().toInt()
    for (i in 1..n) {
        val blk = reader.readLinesX().jsonToBlock()
        chain.assertBlock(blk)
        chain.reheads(blk)
        chain.saveBlock(blk)
        chain.save()
    }
    writer.writeLineX("")
    return n
}

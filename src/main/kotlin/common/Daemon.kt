package org.freechains.common

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

fun daemon (host : Host) {
    val socket = ServerSocket(host.port)
    //System.err.println("host start: $host")

    while (true) {
        try {
            val remote = socket.accept()
            System.err.println("remote connect: $host <- ${remote.inetAddress.hostAddress}")
            thread { handle(socket, remote, host) }
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
            val path = reader.readLineX()
            val chain = local.createChain(path)
            writer.writeLineX(chain.hash)
            System.err.println("chain create: $path")
        }
        "FC chain genesis" -> {
            val path  = reader.readLineX().pathCheck()
            val chain = local.loadChain(path)
            val hash  = chain.toGenHash()
            writer.writeLineX(hash)
            System.err.println("chain genesis: $hash")
        }
        "FC chain heads" -> {
            val path  = reader.readLineX().pathCheck()
            val chain = local.loadChain(path)
            for (head in chain.heads) {
                writer.writeLineX(head)
            }
            writer.writeLineX("")
            System.err.println("chain heads: ${chain.heads}")
        }
        "FC chain get" -> {
            val path = reader.readLineX().pathCheck()
            val hash = reader.readLineX()

            val chain = local.loadChain(path)
            val node  = chain.loadNodeFromHash(hash)
            val json  = node.toJson()

            assert(json.length <= Int.MAX_VALUE)
            println("send $json")
            writer.writeBytes(json)
            writer.writeLineX("\n")
            System.err.println("chain get: $hash")
        }
        "FC chain put" -> {
            val path = reader.readLineX().pathCheck()
            val pay = reader.readLinesX()

            val chain = local.loadChain(path)
            val node = if (local.timestamp) chain.publish(pay) else chain.publish(pay,0)

            writer.writeLineX(node.hash!!)
            System.err.println("chain put: ${node.hash!!}")
        }
        "FC chain send" -> {
            val path = reader.readLineX().pathCheck()
            val host_ = reader.readLineX()

            val chain = local.loadChain(path)
            val (host,port) = host_.hostSplit()

            val socket = Socket(host, port)
            val n = socket.chain_send(chain)
            System.err.println("chain send: $path: $n")
            writer.writeLineX(n.toString())
        }
        "FC chain recv" -> {
            val path = reader.readLineX().pathCheck()
            val chain = local.loadChain(path)
            val n = remote.chain_recv(chain)
            System.err.println("chain recv: $path: $n")
            //writer.writeLineX(ret)
        }
        else -> { error("$ln: invalid header type") }
    }
    remote.close()
}

fun Socket.chain_send (chain: Chain) : Int {
    val reader = DataInputStream(this.getInputStream()!!)
    val writer = DataOutputStream(this.getOutputStream()!!)

    writer.writeLineX("FC chain recv")
    writer.writeLineX(chain.toPath())

    val toSend = mutableSetOf<Hash>()
    fun traverse (hash: Hash) {
        if (toSend.contains(hash)) {
            return
        } else {
            toSend.add(hash)
            val node = chain.loadNodeFromHash(hash)
            for (front in node.fronts) {
                traverse(front)
            }
        }
    }

    while (true) {
        val head = reader.readLineX()
        if (head == "") {
            break
        }
        if (chain.containsNode(head)) {
            val node = chain.loadNodeFromHash(head)
            for (front in node.fronts) {
                traverse(front)
            }
        }
    }

    writer.writeLineX(toSend.size.toString())
    val sorted = toSend.toSortedSet(compareBy({it.length},{it}))
    for (hash in sorted) {
        val node = chain.loadNodeFromHash(hash)
        val new = Node(node.time,node.nonce,node.payload,node.backs, emptyArray())
        new.hash = node.hash!!
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
        val node = reader.readLinesX().jsonToNode()
        node.recheck()
        chain.reheads(node)
        chain.saveNode(node)
        chain.save()
    }
    writer.writeLineX("")
    return n
}

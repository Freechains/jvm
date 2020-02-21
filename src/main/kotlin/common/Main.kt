package org.freechains.common

import org.docopt.Docopt
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Socket
import java.util.Base64

val doc = """
freechains

Usage:
    freechains host create <dir> [<port>]
    freechains host start <dir>
    freechains [options] host stop
    freechains [options] chain join <chain>
    freechains [options] chain join <chain> shared (rw | ro) <shared_key>
    freechains [options] chain join <chain> pubpvt (rw | ro) <public_key> [<private_key>]
    freechains [options] chain genesis <chain>
    freechains [options] chain heads <chain>
    freechains [options] chain get <chain> <height_hash>
    freechains [options] chain put <chain> (file | inline | -) (utf8 | base64) [<path_or_text>]
    freechains [options] chain listen <chain>
    freechains [options] chain send <chain> <host:port>
    freechains [options] crypto create (shared | pubpvt) <passphrase>

Options:
    --help                      [none] displays this help
    --version                   [none] displays version information
    --host=<addr:port>          [all]  sets address and port to connect [default: localhost:8330]
    --time=<ms>                 [put]  sets block timestamp [default: now]
    --encrypt                   [put]  encrypts payload with chain's shared or private key
    --sign=<private_key>        [put]  signs block with given key
    --utf8-eof=<word>           [put]  sets word terminator for utf8 input

More Information:

    http://www.freechains.org/

    Please report bugs at <http://github.com/Freechains/kotlin>.
"""

fun main (args: Array<String>) {
    val ret = main_(args)
    if (ret != null) {
        println(ret)
    }
}

fun main_ (args: Array<String>) : String? {
    val opts = Docopt(doc).withVersion("freechains v0.2").parse(args.toMutableList())

    Thread.setDefaultUncaughtExceptionHandler { _: Thread?, e: Throwable? ->
        System.err.println(
            e!!.message ?: e.toString()
        )
    }

    fun optHost(): Pair<String, Int> {
        return ((opts["--host"] as String?) ?: "localhost:8330").hostSplit()
    }

    when {
        opts["host"] as Boolean ->
            when {
                opts["create"] as Boolean -> {
                    val dir = opts["<dir>"] as String
                    val port = (opts["<port>"] as String?)?.toInt() ?: 8330
                    val host = Host_create(dir, port)
                    System.err.println("host create: $host")
                    return null
                }
                opts["start"] as Boolean -> {
                    val dir = opts["<dir>"] as String
                    val host = Host_load(dir)
                    System.err.println("host start: $host")
                    daemon(host)
                    return null
                }
                opts["stop"] as Boolean -> {
                    val (host, port) = optHost()
                    val socket = Socket(host, port)
                    val writer = DataOutputStream(socket.getOutputStream()!!)
                    val reader = DataInputStream(socket.getInputStream()!!)
                    writer.writeLineX("FC host stop")
                    assert(reader.readLineX() == "true")
                    System.err.println("host stop: $host:$port")
                    socket.close()
                    return null
                }
            }
        opts["chain"] as Boolean -> {
            val (host, port) = optHost()
            val socket = Socket(host, port)
            val writer = DataOutputStream(socket.getOutputStream()!!)
            val reader = DataInputStream(socket.getInputStream()!!)
            when {
                opts["join"] as Boolean -> {
                    writer.writeLineX("FC chain join")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX((opts["ro"] as Boolean).toString())
                    writer.writeLineX(opts["<shared_key>"] as String? ?: "")
                    writer.writeLineX(opts["<public_key>"] as String? ?: "")
                    writer.writeLineX(opts["<private_key>"] as String? ?: "")
                    return reader.readLineX()
                }
                opts["genesis"] as Boolean -> {
                    writer.writeLineX("FC chain genesis")
                    writer.writeLineX(opts["<chain>"] as String)
                    return reader.readLineX()
                }
                opts["heads"] as Boolean -> {
                    writer.writeLineX("FC chain heads")
                    writer.writeLineX(opts["<chain>"] as String)
                    var ret = ""
                    while (true) {
                        val hash = reader.readLineX()
                        if (hash.isEmpty()) {
                            break
                        } else {
                            ret += hash + "\n"
                        }
                    }
                    return if (ret.isEmpty()) null else ret
                }
                opts["get"] as Boolean -> {
                    writer.writeLineX("FC chain get")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX(opts["<height_hash>"] as Hash)
                    val json = reader.readAllBytes().toString(Charsets.UTF_8)
                    if (json.isEmpty()) {
                        System.err.println("chain get: not found")
                        return null
                    } else {
                        return json
                    }
                }
                // freechains [options] chain put <chain> (file | inline | -) (utf8 | base64) [<path_or_text>]
                opts["put"] as Boolean -> {
                    val eof = opts["--utf8-eof"] as String? ?: ""
                    writer.writeLineX("FC chain put")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX(if (opts["utf8"] as Boolean) "utf8"+(if (eof.isEmpty()) "" else " "+eof) else "base64")
                    writer.writeLineX(opts["--time"] as String)
                    writer.writeLineX((opts["--encrypt"] as Boolean).toString())
                    writer.writeLineX((opts["--sign"] as String? ?: ""))

                    val bytes = when {
                        opts["inline"] as Boolean -> (opts["<path_or_text>"] as String).toByteArray()
                        opts["file"] as Boolean -> File(opts["<path_or_text>"] as String).readBytes()
                        else -> error("TODO -")
                    }
                    val payload = when {
                        (opts["inline"] as Boolean || opts["utf8"] as Boolean) -> bytes.toString(Charsets.UTF_8)
                        opts["base64"] as Boolean -> Base64.getEncoder().encode(bytes).toString(Charsets.UTF_8)
                        else -> error("bug found")
                    }
                    //println(payload)
                    writer.writeBytes(payload)

                    writer.writeLineX("\n")
                    val hash = reader.readLineX()
                    return hash
                }
                opts["listen"] as Boolean -> {
                    writer.writeLineX("FC chain listen")
                    writer.writeLineX(opts["<chain>"] as String)
                    while (true) {
                        val n = reader.readLineX()
                        println(n)
                    }
                }
                opts["send"] as Boolean -> {
                    writer.writeLineX("FC chain send")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX(opts["<host:port>"] as String)
                    val ret = reader.readLineX()
                    System.err.println("chain send: $ret")
                    return null
                }
            }
            socket.close()
        }
        opts["crypto"] as Boolean -> {
            val (host, port) = optHost()
            val socket = Socket(host, port)
            val writer = DataOutputStream(socket.getOutputStream()!!)
            val reader = DataInputStream(socket.getInputStream()!!)
            when {
                // freechains [options] crypto create (shared | pubpvt) <passphrase>
                opts["create"] as Boolean -> {
                    val isShared = opts["shared"] as Boolean
                    writer.writeLineX("FC crypto create")
                    writer.writeLineX(if (isShared) "shared" else "pubpvt")
                    writer.writeLineX(opts["<passphrase>"] as String)
                    println(reader.readLineX())         // shared or private key
                    if (isShared) {
                        return null
                    } else {
                        return reader.readLineX()     // public key
                    }
                }
            }
        }
    }
    return null
}

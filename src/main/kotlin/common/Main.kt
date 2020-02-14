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
    freechains [options] chain create <chain/work> [<shared_key>]
    freechains [options] chain genesis <chain/work>
    freechains [options] chain heads <chain/work>
    freechains [options] chain get <chain/work> <height_hash>
    freechains [options] chain put <chain/work> (file | inline | -) (utf8 | base64) [<path_or_text>]
    freechains [options] chain send <chain/work> <host:port>
    freechains [options] chain listen <chain/work>

Options:
    --host=<addr:port>          address and port to connect [default: localhost:8330]
    --help                      display this help
    --version                   display version information

More Information:

    http://www.freechains.org/

    Please report bugs at <http://github.com/Freechains/kotlin>.
"""

fun main (args: Array<String>) {
    //val args_ = arrayOf("host", "start", "/tmp/freechains/8400")
    val args_ = args

    val opts = Docopt(doc).withVersion("freechains v0.2").parse(args_.toMutableList())

    fun optHost () : Pair<String,Int> {
        return ((opts["--host"] as String?) ?: "localhost:8330").hostSplit()
    }

    Thread.setDefaultUncaughtExceptionHandler { _: Thread?, e: Throwable? -> System.err.println(e!!.message) }

    when {
        opts["host"] as Boolean ->
            when {
                opts["create"] as Boolean -> {
                    val dir = opts["<dir>"] as String
                    val port = (opts["<port>"] as String?)?.toInt() ?: 8330
                    val host = Host_create(dir, port)
                    System.err.println("host create: $host")
                }
                opts["start"] as Boolean -> {
                    val dir = opts["<dir>"] as String
                    val host = Host_load(dir)
                    System.err.println("host start: $host")
                    daemon(host)
                }
                opts["stop"] as Boolean -> {
                    val (host, port) = optHost()
                    val socket = Socket(host, port)
                    val writer = DataOutputStream(socket.getOutputStream()!!)
                    val reader = DataInputStream(socket.getInputStream()!!)
                    writer.writeLineX("FC host stop")
                    assert(reader.readLineX() == "1")
                    System.err.println("host stop: $host:$port")
                    socket.close()
                }
            }
        opts["chain"] as Boolean -> {
            val (host, port) = optHost()
            val socket = Socket(host, port)
            val writer = DataOutputStream(socket.getOutputStream()!!)
            val reader = DataInputStream(socket.getInputStream()!!)
            when {
                // freechains [options] chain create <chain/work> [<shared_key>]
                opts["create"] as Boolean -> {
                    writer.writeLineX("FC chain create")
                    writer.writeLineX(opts["<chain/work>"] as String)
                    writer.writeLineX(opts["<shared_key>"] as String? ?: "")
                    println(reader.readLineX())
                }
                opts["genesis"] as Boolean -> {
                    writer.writeLineX("FC chain genesis")
                    writer.writeLineX(opts["<chain/work>"] as String)
                    println(reader.readLineX())
                }
                opts["heads"] as Boolean -> {
                    writer.writeLineX("FC chain heads")
                    writer.writeLineX(opts["<chain/work>"] as String)
                    while (true) {
                        val hash = reader.readLineX()
                        if (hash == "") {
                            break
                        } else {
                            println(hash)
                        }
                    }
                }
                opts["get"] as Boolean -> {
                    writer.writeLineX("FC chain get")
                    writer.writeLineX(opts["<chain/work>"] as String)
                    writer.writeLineX(opts["<height_hash>"] as Hash)
                    val json = reader.readLinesX()
                    if (json == "") {
                        System.err.println("chain get: not found"); -1
                    } else {
                        println(json)
                    }
                }
                // freechains [options] chain put <chain/work> (file | inline | -) (utf8 | base64) [<path_or_text>]
                opts["put"] as Boolean -> {
                    writer.writeLineX("FC chain put")
                    writer.writeLineX(opts["<chain/work>"] as String)
                    writer.writeLineX(if (opts["utf8"] as Boolean) "utf8" else "base64")

                    val bytes = when {
                        opts["inline"] as Boolean -> (opts["<path_or_text>"] as String).toByteArray()
                        opts["file"]   as Boolean -> File(opts["<path_or_text>"] as String).readBytes()
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
                    println(hash)
                }
                opts["send"] as Boolean -> {
                    writer.writeLineX("FC chain send")
                    writer.writeLineX(opts["<chain/work>"] as String)
                    writer.writeLineX(opts["<host:port>"] as String)
                    val ret = reader.readLineX()
                    System.err.println("chain send: $ret")
                }
            }
            socket.close()
        }
        else -> System.err.println("invalid command")
    }
}

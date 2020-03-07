package org.freechains.common

import org.docopt.Docopt
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Socket
import java.util.Base64
import kotlin.io.println as output
import kotlin.io.println as println

val doc = """
freechains

Usage:
    freechains host create <dir> [<port>]
    freechains host start <dir>
    freechains [options] host stop
    freechains [options] host now <time>
    freechains [options] chain join <chain> [ [owner-only] <pub> ]
    freechains [options] chain genesis <chain>
    freechains [options] chain heads (stable | unstable) <chain>
    freechains [options] chain get <chain> <hash>
    freechains [options] chain post <chain> (file | inline | -) (utf8 | base64) [<path_or_text>]
    freechains [options] chain like get <chain> <hash_or_pub>
    freechains [options] chain like post <chain> <integer> <hash_or_pub>
    freechains [options] chain state list <chain> <state>
    freechains [options] chain state get <chain> <state> <hash>
    freechains [options] chain accept <chain> <hash>
    freechains [options] chain remove <chain> <hash>
    freechains [options] chain listen <chain>
    freechains [options] chain send <chain> <host:port>
    freechains [options] crypto create (shared | pubpvt) <passphrase>

Options:
    --help                 [none]           displays this help
    --version              [none]           displays version information
    --host=<addr:port>     [all]            sets address and port to connect [default: localhost:8330]
    --time=<ms>            [post|like]      sets block timestamp [default: now]
    --sign=<pvtkey>        [post|like]      signs post with given private key
    --crypt=<key>          [get|post]       (de|en)crypts post with given shared or private key
    --ref=<hash>           [post]           refers to previous post
    --why=<text>           [like]           explains reason for the like
    --utf8-eof=<word>      [post]           sets word terminator for utf8 post

More Information:

    http://www.freechains.org/

    Please report bugs at <http://github.com/Freechains/kotlin>.
"""

fun main (args: Array<String>) {
    output(main_(args))
}

fun main_ (args: Array<String>) : String {
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
                    return host.toString()
                }
                opts["start"] as Boolean -> {
                    val dir = opts["<dir>"] as String
                    val host = Host_load(dir)
                    System.err.println("host start: $host")
                    Daemon(host).daemon()
                    return "true"
                }
                opts["stop"] as Boolean -> {
                    val (host, port) = optHost()
                    val socket = Socket(host, port)
                    val writer = DataOutputStream(socket.getOutputStream()!!)
                    val reader = DataInputStream(socket.getInputStream()!!)
                    writer.writeLineX("FC host stop")
                    assert(reader.readLineX() == "true")
                    socket.close()
                    return "true"
                }
                opts["now"] as Boolean -> {
                    val (host, port) = optHost()
                    val socket = Socket(host, port)
                    val writer = DataOutputStream(socket.getOutputStream()!!)
                    val reader = DataInputStream(socket.getInputStream()!!)
                    val now= opts["<time>"] as String
                    writer.writeLineX("FC host now")
                    writer.writeLineX(now)
                    assert(reader.readLineX() == "true")
                    socket.close()
                    return "true"
                }
            }
        opts["chain"] as Boolean -> {
            val (host, port) = optHost()
            val socket = Socket(host, port)
            val writer = DataOutputStream(socket.getOutputStream()!!)
            val reader = DataInputStream(socket.getInputStream()!!)
            when {
                //     freechains [options] chain join <chain> [ [owner-only] <pub> ]
                opts["join"] as Boolean -> {
                    writer.writeLineX("FC chain join")
                    writer.writeLineX(opts["<chain>"] as String)
                    if (opts["<pub>"] != null) {
                        writer.writeLineX((opts["owner-only"] as Boolean? ?: false).toString())
                        writer.writeLineX(opts["<pub>"] as String)
                    } else {
                        writer.writeLineX("")
                    }
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
                    writer.writeLineX(if (opts["stable"] as Boolean) "stable" else "unstable")
                    val ret = reader.readLineX()
                    return ret
                }
                opts["like"] as Boolean -> {
                    when {
                        opts["get"] as Boolean -> {
                            writer.writeLineX("FC chain reps")
                            writer.writeLineX(opts["<chain>"] as String)
                            writer.writeLineX(opts["--time"] as String)
                            writer.writeLineX(opts["<hash_or_pub>"] as String)
                            val ret = reader.readLineX()
                            System.err.println("chain reps: $ret")
                            return ret
                        }
                        opts["post"] as Boolean -> {
                            writer.writeLineX("FC chain post")
                            writer.writeLineX(opts["<chain>"] as String)
                            writer.writeLineX(opts["--time"] as String)
                            writer.writeLineX((opts["<hash_or_pub>"] as String))
                            writer.writeLineX(opts["--sign"] as String? ?: "")
                            writer.writeLineX("")   // crypt
                            writer.writeLineX((opts["<integer>"] as String).let {
                                if (it.last() != '-') it else ("-" + it.substring(0, it.length - 1))
                            })
                            writer.writeLineX("utf8")
                            writer.writeLineX((opts["--why"] as String?).let {
                                if (it == null) "" else it + "\n"
                            })

                            val hash = reader.readLineX()
                            return hash
                        }
                    }
                }

                opts["state"] as Boolean -> {
                    when {
                        opts["list"] as Boolean -> {
                            writer.writeLineX("FC chain state list")
                            writer.writeLineX(opts["<chain>"] as String)
                            writer.writeLineX(opts["<state>"] as String)
                            val list = reader.readLineX()
                            return list
                        }
                        opts["get"] as Boolean -> {
                            writer.writeLineX("FC chain get")
                            writer.writeLineX(opts["<chain>"] as String)
                            writer.writeLineX(opts["<state>"] as String)
                            writer.writeLineX(opts["<hash>"] as String)
                            writer.writeLineX("")
                            val json = reader.readAllBytes().toString(Charsets.UTF_8)
                            if (json.isEmpty()) {
                                System.err.println("chain get: not found")
                            }
                            return json
                        }
                    }
                }

                opts["accept"] as Boolean -> {
                    writer.writeLineX("FC chain accept")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX(opts["<hash>"] as String)
                    val ret = reader.readLineX()
                    System.err.println("chain accept: $ret")
                    return ret
                }
                opts["remove"] as Boolean -> {
                    writer.writeLineX("FC chain remove")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX(opts["<hash>"] as String)
                    val rets = reader.readLinesX()
                    System.err.println("chain remove: ...")
                    return rets
                }

                opts["get"] as Boolean -> {
                    writer.writeLineX("FC chain get")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX("block")
                    writer.writeLineX(opts["<hash>"] as Hash)
                    writer.writeLineX(opts["--crypt"] as String? ?: "")
                    val json = reader.readAllBytes().toString(Charsets.UTF_8)
                    if (json.isEmpty()) {
                        System.err.println("chain get: not found")
                    }
                    return json
                }
                // freechains [options] chain post <chain> (file | inline | -) (utf8 | base64) [<path_or_text>]
                opts["post"] as Boolean -> {
                    val eof = opts["--utf8-eof"] as String? ?: ""
                    writer.writeLineX("FC chain post")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX(opts["--time"] as String)
                    writer.writeLineX(opts["--ref"] as String? ?: "")
                    writer.writeLineX(opts["--sign"] as String? ?: "")
                    writer.writeLineX((opts["--crypt"] as String? ?: "").toString())
                    writer.writeLineX("0")
                    writer.writeLineX(if (opts["utf8"] as Boolean) "utf8"+(if (eof.isEmpty()) "" else " "+eof) else "base64")

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
                    writer.writeBytes(payload)
                    if (eof.isEmpty()) {
                        writer.writeLineX("\n")
                    }

                    val hash = reader.readLineX()
                    return hash
                }
                opts["listen"] as Boolean -> {
                    writer.writeLineX("FC chain listen")
                    writer.writeLineX(opts["<chain>"] as String)
                    while (true) {
                        val n = reader.readLineX()
                        output(n)
                    }
                }
                opts["send"] as Boolean -> {
                    writer.writeLineX("FC chain send")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX(opts["<host:port>"] as String)
                    return reader.readLineX()
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
                    return reader.readLineX()
                }
            }
        }
    }
    error("bug found")
}

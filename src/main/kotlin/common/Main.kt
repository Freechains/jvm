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
    freechains [options] host flush
    freechains [options] chain join <chain>
    freechains [options] chain join <chain> shared <shared_key>
    freechains [options] chain join <chain> pubpvt [owner-only] <public_key> [<private_key>]
    freechains [options] chain genesis <chain>
    freechains [options] chain heads <chain>
    freechains [options] chain get <chain> <hash>
    freechains [options] chain post <chain> (file | inline | -) (utf8 | base64) [<path_or_text>]
    freechains [options] chain like get <chain> <public_key>
    freechains [options] chain like post <chain> <integer> (<hash> | <public_key>)
    freechains [options] chain listen <chain>
    freechains [options] chain send <chain> <host:port>
    freechains [options] crypto create (shared | pubpvt) <passphrase>

Options:
    --help                 [none]        displays this help
    --version              [none]        displays version information
    --host=<addr:port>     [all]         sets address and port to connect [default: localhost:8330]
    --time=<ms>            [post|like]   sets block timestamp [default: now]
    --sign=<private_key>   [post|like]   signs block with given key
    --utf8-eof=<word>      [post]        sets word terminator for utf8 post
    --encrypt              [post]        encrypts post with chain's private key (shared is always encrypted)
    --ref=<hash>           [post]        refers to previous post
    --why=<text>           [like]        explains reason for the like

More Information:

    http://www.freechains.org/

    Please report bugs at <http://github.com/Freechains/kotlin>.
"""

fun main (args: Array<String>) {
    val ret = main_(args)
    if (ret != null) {
        output(ret)
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
                    return host.toString()
                }
                opts["start"] as Boolean -> {
                    val dir = opts["<dir>"] as String
                    val host = Host_load(dir)
                    System.err.println("host start: $host")
                    Daemon(host).daemon()
                    return null
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
                opts["flush"] as Boolean -> {
                    val (host, port) = optHost()
                    val socket = Socket(host, port)
                    val writer = DataOutputStream(socket.getOutputStream()!!)
                    val reader = DataInputStream(socket.getInputStream()!!)
                    writer.writeLineX("FC host flush")
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
                opts["join"] as Boolean -> {
                    writer.writeLineX("FC chain join")
                    writer.writeLineX(opts["<chain>"] as String)
                    when {
                        opts["shared"] as Boolean -> {
                            writer.writeLineX("shared")
                            writer.writeLineX(opts["<shared_key>"] as String)
                        }
                        opts["pubpvt"] as Boolean -> {
                            writer.writeLineX("pubpvt")
                            writer.writeLineX((opts["owner-only"] as Boolean? ?: false).toString())
                            writer.writeLineX(opts["<public_key>"] as String? ?: "")
                            writer.writeLineX(opts["<private_key>"] as String? ?: "")
                        }
                        else -> {
                            writer.writeLineX("")
                        }
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
                opts["like"] as Boolean -> {
                    when {
                        opts["get"] as Boolean -> {
                            writer.writeLineX("FC chain reps")
                            writer.writeLineX(opts["<chain>"] as String)
                            writer.writeLineX(opts["--time"] as String)
                            writer.writeLineX(opts["<public_key>"] as String)
                            val ret = reader.readLineX()
                            System.err.println("chain reps: $ret")
                            return ret
                        }
                        opts["post"] as Boolean -> {
                            writer.writeLineX("FC chain post")
                            writer.writeLineX(opts["<chain>"] as String)
                            writer.writeLineX(opts["--time"] as String)
                            writer.writeLineX((opts["<integer>"] as String).let {
                                if (it.last() != '-') it else ("-" + it.substring(0, it.length - 1))
                            })
                            writer.writeLineX("utf8")
                            writer.writeLineX("false")
                            writer.writeLineX((opts["--why"] as String?).let {
                                if (it == null) "" else it + "\n"
                            })
                            writer.writeLineX((opts["<hash>"] as String? ?: opts["<public_key>"] as String) + "\n")
                            writer.writeLineX(opts["--sign"] as String? ?: "")

                            writer.writeLineX("\n")
                            val hash = reader.readLineX()
                            return hash
                        }
                    }
                }
                opts["get"] as Boolean -> {
                    writer.writeLineX("FC chain get")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX(opts["<hash>"] as Hash)
                    val json = reader.readAllBytes().toString(Charsets.UTF_8)
                    if (json.isEmpty()) {
                        System.err.println("chain get: not found")
                        return null
                    } else {
                        return json
                    }
                }
                // freechains [options] chain post <chain> (file | inline | -) (utf8 | base64) [<path_or_text>]
                opts["post"] as Boolean -> {
                    val eof = opts["--utf8-eof"] as String? ?: ""
                    writer.writeLineX("FC chain post")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX(opts["--time"] as String)
                    writer.writeLineX("0")
                    writer.writeLineX(if (opts["utf8"] as Boolean) "utf8"+(if (eof.isEmpty()) "" else " "+eof) else "base64")
                    writer.writeLineX((opts["--encrypt"] as Boolean).toString())

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
                    writer.writeLineX("\n")

                    writer.writeLineX((opts["--ref"] as String?).let {
                        if (it == null) "" else it + "\n"
                    })
                    writer.writeLineX((opts["--sign"] as String? ?: ""))

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
                    output(reader.readLineX())         // shared or private key
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

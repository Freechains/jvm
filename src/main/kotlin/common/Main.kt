package org.freechains.common

import org.freechains.platform.*

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
    
    freechains [options] crypto create (shared | pubpvt) <passphrase>
    
    freechains [options] chains join <chain> [trusted] [ [owner-only] <pub> ]
    freechains [options] chains leave <chain>
    freechains [options] chains list
    freechains [options] chains listen
    
    freechains [options] chain genesis <chain>
    freechains [options] chain heads <chain> (all | linked | blocked)
    freechains [options] chain get <chain> (block | payload) <hash>
    freechains [options] chain post <chain> (file | inline | -) [<path_or_text>]
    freechains [options] chain (like | dislike) <chain> <hash>
    freechains [options] chain reps <chain> <hash_or_pub>
    freechains [options] chain remove <chain> <hash>
    freechains [options] chain traverse <chain> (all | linked) <hashes>...
    freechains [options] chain listen <chain>
    
    freechains [options] peer ping <host:port>
    freechains [options] peer chains <host:port>
    freechains [options] peer (send | recv) <host:port> <chain>

Options:
    --help              [none]            displays this help
    --version           [none]            displays version information
    --host=<addr:port>  [all]             sets address and port to connect [default: localhost:${PORT_8330}]
    --sign=<pvtkey>     [post|(dis)like]  signs post with given private key
    --crypt=<key>       [get|post]        (de|en)crypts post with given shared or private key
    --why=<text>        [(dis)like]       explains reason for the like

More Information:

    http://www.freechains.org/

    Please report bugs at <http://github.com/Freechains/jvm>.
"""

fun main (args: Array<String>) {
    //println(args.contentToString())
    output(main_(args))
}

fun main_ (args: Array<String>) : String {
    val opts = Docopt(doc).withVersion("freechains $VERSION").parse(args.toMutableList())

    Thread.setDefaultUncaughtExceptionHandler { _: Thread?, e: Throwable? ->
        System.err.println(e!!.message ?: e.toString())
        //System.err.println(e.stackTrace.contentToString())
    }

    fun optHost(): Pair<String, Int> {
        return ((opts["--host"] as String?) ?: "localhost:$PORT_8330").hostSplit()
    }

    // host create/start do not connect to daemon

    when {
        opts["host"] as Boolean ->
            when {
                opts["create"] as Boolean -> {
                    val dir = opts["<dir>"] as String
                    val port = (opts["<port>"] as String?)?.toInt() ?: PORT_8330
                    val host = Host_create(dir, port)
                    return host.toString()
                }
                opts["start"] as Boolean -> {
                    val dir = opts["<dir>"] as String
                    val host = Host_load(dir)
                    output("Freechains $VERSION")
                    output("Waiting for connections on port ${host.port}...")
                    Daemon(host).daemon()
                    return "true"
                }
            }
    }

    // all remaining connect to daemon

    val (host, port) = optHost()
    val socket = Socket_5s(host, port)
    val writer = DataOutputStream(socket.getOutputStream()!!)
    val reader = DataInputStream(socket.getInputStream()!!)

    when {
        opts["host"] as Boolean ->
            when {
                opts["stop"] as Boolean -> {
                    writer.writeLineX("$PRE host stop")
                    assert(reader.readLineX() == "true")
                    socket.close()
                    return "true"
                }
                opts["now"] as Boolean -> {
                    val now= opts["<time>"] as String
                    writer.writeLineX("$PRE host now")
                    writer.writeLineX(now)
                    assert(reader.readLineX() == "true")
                    socket.close()
                    return "true"
                }
            }
        opts["peer"] as Boolean -> {
            when {
                opts["ping"] as Boolean -> {
                    val now = getNow()
                    writer.writeLineX("$PRE peer ping")
                    reader.readLineX()
                    return (getNow() - now).toString()
                }
                opts["chains"] as Boolean -> {
                    writer.writeLineX("$PRE peer chains")
                    return reader.readLineX()
                }
                opts["send"] as Boolean -> {
                    writer.writeLineX("$PRE peer send")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX(opts["<host:port>"] as String)
                    return reader.readLineX()
                }
                opts["recv"] as Boolean -> {
                    writer.writeLineX("$PRE peer recv")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX(opts["<host:port>"] as String)
                    return reader.readLineX()
                }
            }
        }
        opts["crypto"] as Boolean -> {
            when {
                // freechains [options] crypto create (shared | pubpvt) <passphrase>
                opts["create"] as Boolean -> {
                    val isShared = opts["shared"] as Boolean
                    writer.writeLineX("$PRE crypto create")
                    writer.writeLineX(if (isShared) "shared" else "pubpvt")
                    writer.writeLineX(opts["<passphrase>"] as String)
                    return reader.readLineX()
                }
            }
        }
        opts["chains"] as Boolean -> {
            when {
                opts["join"] as Boolean -> {
                    writer.writeLineX("$PRE chains join")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX((opts["trusted"] as Boolean).toString())
                    if (opts["<pub>"] != null) {
                        writer.writeLineX((opts["owner-only"] as Boolean? ?: false).toString())
                        writer.writeLineX(opts["<pub>"] as String)
                    } else {
                        writer.writeLineX("")
                    }
                    return reader.readLineX()
                }
                opts["leave"] as Boolean -> {
                    writer.writeLineX("$PRE chains leave")
                    writer.writeLineX(opts["<chain>"] as String)
                    return reader.readLineX()
                }
                opts["list"] as Boolean -> {
                    writer.writeLineX("$PRE chains list")
                    return reader.readLineX()
                }
                opts["listen"] as Boolean -> {
                    writer.writeLineX("$PRE chains listen")
                    while (true) {
                        val n_name = reader.readLineX()
                        output(n_name)
                    }
                }
            }
        }
        opts["chain"] as Boolean -> {
            when {
                opts["genesis"] as Boolean -> {
                    writer.writeLineX("$PRE chain genesis")
                    writer.writeLineX(opts["<chain>"] as String)
                    return reader.readLineX()
                }
                opts["heads"] as Boolean -> {
                    writer.writeLineX("$PRE chain heads")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX (
                        when {
                            opts["all"]     as Boolean -> "all"
                            opts["linked"]  as Boolean -> "linked"
                            opts["blocked"] as Boolean -> "blocked"
                            else -> error("bug found")
                        }
                    )
                    return reader.readLineX()
                }
                opts["reps"] as Boolean -> {
                    writer.writeLineX("$PRE chain reps")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX(opts["<hash_or_pub>"] as String)
                    return reader.readLineX()
                }

                opts["get"] as Boolean -> {
                    writer.writeLineX("$PRE chain get")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX (
                        when {
                            opts["block"]   as Boolean -> "block"
                            opts["payload"] as Boolean -> "payload"
                            else -> error("bug found")
                        }
                    )
                    writer.writeLineX(opts["<hash>"] as Hash)
                    writer.writeLineX(opts["--crypt"] as String? ?: "")
                    val len = reader.readLineX().toInt()
                    val json = reader.readNBytesX(len).toString(Charsets.UTF_8)
                    if (json.isEmpty()) {
                        System.err.println("not found")
                    }
                    return json
                }

                opts["remove"] as Boolean -> {
                    writer.writeLineX("$PRE chain remove")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX(opts["<hash>"] as Hash)
                    assert(reader.readLineX() == "true")
                    return "true"
                }

                opts["like"]    as Boolean ||
                opts["dislike"] as Boolean -> {
                    val lk = if (opts["like"] as Boolean) "+1" else "-1"
                    assert(opts["--sign"] is String) { "expected `--sign`" }
                    writer.writeLineX("$PRE chain post")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX(opts["--sign"] as String)
                    writer.writeLineX("")   // crypt
                    writer.writeLineX(lk)
                    writer.writeLineX((opts["<hash>"] as String))
                    (opts["--why"] as String?).let {
                        if (it == null) {
                            writer.writeLineX("0")
                            writer.writeLineX("")
                        } else {
                            writer.writeLineX(it.length.toString())
                            writer.writeLineX(it + "\n")
                        }
                    }
                    return reader.readLineX()
                }
                opts["post"] as Boolean -> {
                    writer.writeLineX("$PRE chain post")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX(opts["--sign"] as String? ?: "")
                    writer.writeLineX((opts["--crypt"] as String? ?: "").toString())
                    writer.writeLineX("0")
                    writer.writeLineX("")

                    val pay = when {
                        opts["inline"] as Boolean -> (opts["<path_or_text>"] as String)
                        opts["file"]   as Boolean -> File(opts["<path_or_text>"] as String).readBytes().toString(Charsets.UTF_8)
                        opts["-"]      as Boolean -> DataInputStream(System.`in`).readAllBytes()!!.toString(Charsets.UTF_8)
                        else -> error("impossible case")
                    }
                    writer.writeLineX(pay.length.toString())
                    writer.writeBytes(pay)
                    writer.writeLineX("")

                    val hash = reader.readLineX()
                    return hash
                }
                // freechains chain traverse <chain> (all | linked) { <hash> }
                opts["traverse"] as Boolean -> {
                    writer.writeLineX("$PRE chain traverse")
                    writer.writeLineX(opts["<chain>"] as String)
                    writer.writeLineX (
                        when {
                            opts["all"]     as Boolean -> "all"
                            opts["linked"]  as Boolean -> "linked"
                            opts["blocked"] as Boolean -> "blocked"
                            else -> error("bug found")
                        }
                    )
                    writer.writeLineX (
                        (opts["<hashes>"] as ArrayList<Any?>)
                            .filterIsInstance<String>()
                            .joinToString(" ")
                    )
                    val ret = reader.readLineX()
                    return ret
                }
                opts["listen"] as Boolean -> {
                    writer.writeLineX("$PRE chain listen")
                    writer.writeLineX(opts["<chain>"] as String)
                    while (true) {
                        val n = reader.readLineX()
                        output(n)
                    }
                }
            }
        }
    }
    error("bug found")
}

@file:Suppress("UnusedImport")

package org.freechains.common

import org.freechains.platform.fsRoot
import org.freechains.platform.readNBytesX
import org.freechains.platform.readAllBytesX
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.system.exitProcess

val doc = """
freechains $VERSION

Usage:
    freechains host start <dir> [<port>]
    freechains host stop
    freechains host now <time>
    
    freechains crypto create (shared | pubpvt) <passphrase>
    
    freechains chains join  <chain>
    freechains chains leave <chain>
    freechains chains list
    freechains chains listen
    
    freechains chain <chain> genesis
    freechains chain <chain> heads (all | linked | blocked)
    freechains chain <chain> get (block | payload) <hash>
    freechains chain <chain> post (file | inline | -) [<path_or_text>]
    freechains chain <chain> (like | dislike) <hash>
    freechains chain <chain> reps <hash_or_pub>
    freechains chain <chain> remove <hash>
    freechains chain <chain> traverse (all | linked) <hashes>...
    freechains chain <chain> listen
    
    freechains peer <host:port> ping
    freechains peer <host:port> chains
    freechains peer <host:port> (send | recv) <chain>

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
    //val xxx = fsRoot
    main_(args).let { (ok,msg) ->
        if (ok) {
            if (msg != null) {
                println(msg)
            }
        } else {
            System.err.println("! " + if (msg != null) msg else "unknown error")
            exitProcess(1)
        }
    }
}

fun main_ (args: Array<String>) : Pair<Boolean,String?> {
    val cmds = args.filter { !it.startsWith("--") }
    val opts = args
        .filter { it.startsWith("--") }
        .map {
            if (it.contains('=')) {
                val (k,v) = Regex("(--.*)=(.*)").find(it)!!.destructured
                Pair(k,v)
            } else {
                Pair(it, null)
            }
        }
        .toMap()

    //println(cmds)
    //println(opts)

    when (cmds[0]) {
        "host" -> {
            assert(cmds.size >= 2)
            when (cmds[1]) {
                "start" -> {
                    assert(cmds.size==3 || cmds.size==4)
                    val dir= cmds[2]
                    val port = if (cmds.size==4) cmds[3].toInt() else PORT_8330
                    val host = Host_load(dir, port)
                    println("Freechains $VERSION")
                    println("Waiting for connections on $host...")
                    Daemon(host).daemon()
                    return Pair(true,null)
                }
            }
        }
    }

    try {
        val (host, port) = (opts["--host"] ?: "localhost:$PORT_8330").hostSplit()
        val socket = Socket_5s(host, port)
        val writer = DataOutputStream(socket.getOutputStream()!!)
        val reader = DataInputStream(socket.getInputStream()!!)

        when (cmds[0]) {
            "host" -> {
                assert(cmds.size in 2..4)
                when (cmds[1]) {
                    "stop" -> {
                        assert(cmds.size == 2)
                        writer.writeLineX("$PRE host stop")
                        assert(reader.readLineX() == "true")
                        socket.close()
                        return Pair(true, null)
                    }
                    "now" -> {
                        assert(cmds.size == 3)
                        val now = cmds[2]
                        writer.writeLineX("$PRE host now")
                        writer.writeLineX(now)
                        assert(reader.readLineX() == "true")
                        socket.close()
                        return Pair(true, null)
                    }
                }
            }
            "crypto" -> {
                when (cmds[1]) {
                    "create" -> {
                        val mode = cmds[2]
                        val pass = cmds[3]
                        writer.writeLineX("$PRE crypto create")
                        writer.writeLineX(mode)
                        writer.writeLineX(pass)
                        val ret = reader.readLineX()
                        return Pair(true,ret)
                    }
                }
            }
            "peer" -> {
                assert(cmds.size in 3..4)
                val remote = cmds[1]
                when (cmds[2]) {
                    "ping" -> {
                        assert(cmds.size == 3)
                        writer.writeLineX("$PRE peer ping")
                        writer.writeLineX(remote)
                        val ret = reader.readLineX()
                        return Pair(true, ret)
                    }
                    "chains" -> {
                        assert(cmds.size == 3)
                        writer.writeLineX("$PRE peer chains")
                        writer.writeLineX(remote)
                        val ret = reader.readLineX()
                        return Pair(true,ret)
                    }
                    "send" -> {
                        assert(cmds.size == 4)
                        val chain = cmds[3]
                        writer.writeLineX("$PRE peer send")
                        writer.writeLineX(chain)
                        writer.writeLineX(remote)
                        val ret = reader.readLineX()
                        return Pair(true,ret)
                    }
                    "recv" -> {
                        assert(cmds.size == 4)
                        val chain = cmds[3]
                        writer.writeLineX("$PRE peer recv")
                        writer.writeLineX(chain)
                        writer.writeLineX(remote)
                        val ret = reader.readLineX()
                        return Pair(true,ret)
                    }
                }
            }
            "chains" -> {
                assert(cmds.size in 2..3)
                when (cmds[1]) {
                    "list" -> {
                        assert(cmds.size == 2)
                        writer.writeLineX("$PRE chains list")
                        val ret = reader.readLineX()
                        return Pair(true,ret)
                    }
                    "leave" -> {
                        assert(cmds.size == 3)
                        writer.writeLineX("$PRE chains leave")
                        writer.writeLineX(cmds[2])
                        val ret = reader.readLineX()
                        return Pair(true,ret)
                    }
                    "join" -> {
                        assert(cmds.size == 3)
                        writer.writeLineX("$PRE chains join")
                        writer.writeLineX(cmds[2])
                        val ret = reader.readLineX()
                        return Pair(true,ret)
                    }
                }
            }
            "chain" -> {
                assert(cmds.size >= 3)
                val chain = cmds[1]

                fun like (lk: String) : Pair<Boolean,String?> {
                    assert(cmds.size == 4)
                    assert(opts["--sign"] is String) { "expected `--sign`" }
                    val hash = cmds[3]
                    writer.writeLineX("$PRE chain post")
                    writer.writeLineX(chain)
                    writer.writeLineX(opts["--sign"] as String)
                    writer.writeLineX("")   // crypt
                    writer.writeLineX(lk)
                    writer.writeLineX(hash)
                    opts["--why"].let {
                        if (it == null) {
                            writer.writeLineX("0")
                            writer.writeLineX("")
                        } else {
                            writer.writeLineX(it.length.toString())
                            writer.writeLineX(it + "\n")
                        }
                    }
                    val ret = reader.readLineX()
                    return Pair(true,ret)
                }

                when (cmds[2]) {
                    "genesis" -> {
                        assert(cmds.size == 3)
                        writer.writeLineX("$PRE chain genesis")
                        writer.writeLineX(chain)
                        val ret = reader.readLineX()
                        return Pair(true, ret)
                    }
                    "heads" -> {
                        assert(cmds.size == 4)
                        writer.writeLineX("$PRE chain heads")
                        writer.writeLineX(chain)
                        writer.writeLineX(cmds[3])
                        val ret = reader.readLineX()
                        return Pair(true,ret)
                    }
                    "get" -> {
                        assert(cmds.size == 5)
                        writer.writeLineX("$PRE chain get")
                        writer.writeLineX(chain)
                        writer.writeLineX(cmds[3])
                        writer.writeLineX(cmds[4])
                        writer.writeLineX(opts["--crypt"] ?: "")
                        val len = reader.readLineX().toInt()
                        val json = reader.readNBytesX(len).toString(Charsets.UTF_8)
                        if (json.isEmpty()) {
                            System.err.println("not found")
                        }
                        return Pair(true,json)
                    }
                    "post" -> {
                        assert(cmds.size in 4..5)
                        writer.writeLineX("$PRE chain post")
                        writer.writeLineX(chain)
                        writer.writeLineX(opts["--sign"] ?: "")
                        writer.writeLineX((opts["--crypt"] ?: "").toString())
                        writer.writeLineX("0")
                        writer.writeLineX("")

                        val pay = when (cmds[3]) {
                            "inline" -> cmds[4]
                            "file"   -> File(cmds[4]).readBytes().toString(Charsets.UTF_8)
                            "-"      -> DataInputStream(System.`in`).readAllBytesX().toString(Charsets.UTF_8)
                            else -> error("impossible case")
                        }
                        writer.writeLineX(pay.length.toString())
                        writer.writeBytes(pay)
                        writer.writeLineX("")

                        val ret = reader.readLineX()
                        println("posted $ret")
                        return Pair(true,ret)
                    }
                    "traverse" -> {
                        assert(cmds.size >= 5)
                        writer.writeLineX("$PRE chain traverse")
                        writer.writeLineX(chain)
                        writer.writeLineX(cmds[3])
                        writer.writeLineX(cmds.drop(3).joinToString(" "))
                        val ret = reader.readLineX()
                        return Pair(true,ret)
                    }
                    "reps" -> {
                        assert(cmds.size == 4)
                        writer.writeLineX("$PRE chain reps")
                        writer.writeLineX(chain)
                        writer.writeLineX(cmds[3])
                        val ret = reader.readLineX()
                        return Pair(true,ret)
                    }
                    "like"    -> return like("1")
                    "dislike" -> return like("-1")
                    "remove" -> {
                        assert(cmds.size == 4)
                        writer.writeLineX("$PRE chain remove")
                        writer.writeLineX(chain)
                        writer.writeLineX(cmds[3])
                        assert(reader.readLineX() == "true")
                        return Pair(true,null)
                    }

                }
            }
        }
    } catch (e: Exception) {
        return Pair(false,e.message)
    }
    error("bug found")

    /*
    try {
        val opts = Docopt(doc).withVersion("freechains $VERSION").parse(args.toMutableList())


        // host start does not connect to daemon

        // all remaining connect to daemon

        when {
            opts["chains"] as Boolean -> {
                when {
                    opts["listen"] as Boolean -> {
                        writer.writeLineX("$PRE chains listen")
                        while (true) {
                            val n_name = reader.readLineX()
                            println(n_name)
                        }
                    }
                }
            }
            opts["chain"] as Boolean -> {
                when {
                    opts["listen"] as Boolean -> {
                        writer.writeLineX("$PRE chain listen")
                        writer.writeLineX(opts["<chain>"] as String)
                        while (true) {
                            val n = reader.readLineX()
                            println(n)
                        }
                    }
                }
            }
        }
        error("bug found")
    } catch (e: Throwable) {
        return Pair(false,e.message)
    }
     */
}

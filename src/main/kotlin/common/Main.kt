@file:Suppress("UnusedImport")

package org.freechains.common

import org.freechains.platform.readNBytesX
import org.freechains.platform.readAllBytesX
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.system.exitProcess

const val help = """
freechains $VERSION

Usage:
    freechains host start <dir> [<port>]
    freechains host stop
    freechains host now <time>
    
    freechains crypto create (shared | pubpvt) <passphrase>
    
    freechains chains join  <chain> [<shared>]
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
    --encrypt           [post]            encrypts post with public key (in public identity chain)
    --decrypt=<pvtkey>] [get]             decrypts post with private key (in public identity chain)
    --why=<text>        [(dis)like]       explains reason for the like

More Information:

    http://www.freechains.org/

    Please report bugs at <http://github.com/Freechains/jvm>.
"""

fun main (args: Array<String>) {
    main_(args).let { (ok,msg) ->
        if (ok) {
            if (msg.isNotEmpty()) {
                println(msg)
            }
        } else {
            System.err.println(msg)
            exitProcess(1)
        }
    }
}

fun main_ (args: Array<String>) : Pair<Boolean,String> {
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

    val CMD = "freechains ${args.joinToString(" ")}"
    //println(">>> $cmds")
    //println(">>> $opts")

    when {
        opts.containsKey("--help") -> return Pair(true,  help)
        (cmds.size == 0)           -> return Pair(false, help)
    }

    try {
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
                        return Pair(true,"")
                    }
                }
            }
        }

        val (host, port) = (opts["--host"] ?: "localhost:$PORT_8330").hostSplit()
        val socket = Socket_5s(host, port)
        val writer = DataOutputStream(socket.getOutputStream()!!)
        val reader = DataInputStream(socket.getInputStream()!!)

        val ret : String = when (cmds[0]) {
            "host" -> {
                assert(cmds.size in 2..4)
                when (cmds[1]) {
                    "stop" -> {
                        assert(cmds.size == 2)
                        writer.writeLineX("$PRE host stop")
                        assert(reader.readLineX() == "true")
                        socket.close()
                        ""
                    }
                    "now" -> {
                        assert(cmds.size == 3)
                        writer.writeLineX("$PRE host now ${cmds[2]}")
                        assert(reader.readLineX() == "true")
                        socket.close()
                        ""
                    }
                    else -> ""
                }
            }
            "crypto" -> {
                when (cmds[1]) {
                    "create" -> {
                        writer.writeLineX("$PRE crypto create ${cmds[2]} ${cmds[3]}")
                        reader.readLineX()
                    }
                    else -> "!"
                }
            }
            "peer" -> {
                assert(cmds.size in 3..4)
                val remote = cmds[1]
                when (cmds[2]) {
                    "ping" -> {
                        assert(cmds.size == 3)
                        writer.writeLineX("$PRE peer $remote ping")
                        reader.readLineX()
                    }
                    "chains" -> {
                        assert(cmds.size == 3)
                        writer.writeLineX("$PRE peer $remote chains")
                        reader.readLineX()
                    }
                    "send" -> {
                        assert(cmds.size == 4)
                        writer.writeLineX("$PRE peer $remote send ${cmds[3]}")
                        reader.readLineX()
                    }
                    "recv" -> {
                        assert(cmds.size == 4)
                        writer.writeLineX("$PRE peer $remote recv ${cmds[3]}")
                        reader.readLineX()
                    }
                    else -> "!"
                }
            }
            "chains" -> {
                assert(cmds.size in 2..4)
                when (cmds[1]) {
                    "list" -> {
                        assert(cmds.size == 2)
                        writer.writeLineX("$PRE chains list")
                        reader.readLineX()
                    }
                    "leave" -> {
                        assert(cmds.size == 3)
                        writer.writeLineX("$PRE chains leave ${cmds[2]}")
                        reader.readLineX()
                    }
                    "join" -> {
                        assert(cmds.size in 3..4)
                        val shared = if (cmds.size == 3) "" else " "+cmds[3]
                        writer.writeLineX("$PRE chains join ${cmds[2]}$shared")
                        reader.readLineX()
                    }
                    "listen" -> {
                        assert(cmds.size == 2)
                        writer.writeLineX("$PRE chains listen")
                        while (true) {
                            val n_name = reader.readLineX()
                            println(n_name)
                        }
                        "!"
                    }
                    else -> "!"
                }
            }
            "chain" -> {
                assert(cmds.size >= 3)
                val chain = cmds[1]

                fun like (lk: String) : String {
                    assert(cmds.size == 4)
                    assert(opts["--sign"] is String) { "expected `--sign`" }
                    val (len,pay) = opts["--why"].let {
                        if (it == null) {
                            Pair(0, "")
                        } else {
                            Pair(it.length.toString(), it)
                        }
                    }
                    writer.writeLineX("$PRE chain $chain like $lk ${cmds[3]} ${opts["--sign"]} $len")
                    writer.writeLineX(pay)
                    return reader.readLineX()
                }

                when (cmds[2]) {
                    "genesis" -> {
                        assert(cmds.size == 3)
                        writer.writeLineX("$PRE chain $chain genesis")
                        reader.readLineX()
                    }
                    "heads" -> {
                        assert(cmds.size == 4)
                        writer.writeLineX("$PRE chain $chain heads ${cmds[3]}")
                        reader.readLineX()
                    }
                    "get" -> {
                        assert(cmds.size == 5)
                        val decrypt= opts["--decrypt"].toString() // null or pvtkey

                        writer.writeLineX("$PRE chain $chain get ${cmds[3]} ${cmds[4]} $decrypt")
                        val len = reader.readLineX()
                        if (len.startsWith('!')) {
                            len
                        } else {
                            reader.readNBytesX(len.toInt()).toString(Charsets.UTF_8)
                        }
                    }
                    "post" -> {
                        assert(cmds.size in 4..5)
                        val sign  = opts["--sign"]  ?: "anon"
                        val encrypt = opts.containsKey("--encrypt").toString() // null (false) or empty (true)

                        val pay = when (cmds[3]) {
                            "inline" -> cmds[4]
                            "file"   -> File(cmds[4]).readBytes().toString(Charsets.UTF_8)
                            "-"      -> DataInputStream(System.`in`).readAllBytesX().toString(Charsets.UTF_8)
                            else -> error("impossible case")
                        }

                        writer.writeLineX("$PRE chain $chain post $sign $encrypt ${pay.length}")
                        writer.writeBytes(pay)

                        reader.readLineX()
                    }
                    "traverse" -> {
                        assert(cmds.size >= 5)
                        val downto = cmds.drop(3).joinToString(" ")
                        writer.writeLineX("$PRE chain $chain traverse ${cmds[3]} $downto")
                        reader.readLineX()
                    }
                    "reps" -> {
                        assert(cmds.size == 4)
                        writer.writeLineX("$PRE chain $chain reps ${cmds[3]}")
                        reader.readLineX()
                    }
                    "like"    -> like("1")
                    "dislike" -> like("-1")
                    "remove" -> {
                        assert(cmds.size == 4)
                        writer.writeLineX("$PRE chain $chain remove ${cmds[3]}")
                        assert(reader.readLineX() == "true")
                        ""
                    }
                    "listen" -> {
                        assert(cmds.size == 3)
                        writer.writeLineX("$PRE chain $chain listen")
                        while (true) {
                            val n = reader.readLineX()
                            println(n)
                        }
                        "!"
                    }
                    else -> "!"
                }
            }
            else -> "!"
        }
        return when {
            ret.equals("!")            -> Pair(false, "! " + CMD)
            ret.startsWith("!") -> Pair(false, ret)
            else                       -> Pair(true, ret)
        }
    } catch (e: AssertionError) {
        return Pair(false, "! " + if (e.message.equals("Assertion failed")) CMD else e.message!!)
    } catch (e: ConnectException) {
        assert(e.message == "Connection refused (Connection refused)")
        return Pair(false, "! connection refused")
    } catch (e: SocketTimeoutException) {
        return Pair(false, "! connection timeout")
    } catch (e: Throwable) {
        return Pair(false, "! TODO - $e - ${e.message} - ($CMD)")
    }

}

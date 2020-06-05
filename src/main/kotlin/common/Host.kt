package org.freechains.common

import org.freechains.platform.fsRoot
import java.io.File

data class Host (
    val root: String,
    val port: Int
)

fun Host_load (dir: String, port: Int = PORT_8330) : Host {
    assert(dir.startsWith("/")) { "path must be absolute" }
    val host = Host(fsRoot+dir+"/", port)
    File(host.root).let {
        if (!it.exists()) {
            it.mkdirs()
        }
    }
    return host
}

fun String.hostSplit () : Pair<String,Int> {
    val lst = this.split(":")
    return when (lst.size) {
        0 -> Pair("localhost", PORT_8330)
        1 -> Pair(lst[0], PORT_8330)
        else -> Pair(lst[0], lst[1].toInt())
    }
}

// CHAINS

fun Host.chainsLoad (name: String) : Chain {
    val file = File(this.root + "/chains/" + name + "/" + "chain")
    val chain = file.readText().fromJsonToChain()
    chain.root = this.root
    return chain
}

fun Host.chainsJoin (name: String) : Chain {
    val chain = Chain(this.root,name).validate()
    val file = File(chain.path() + "/chain")
    assert(!file.exists()) { "chain already exists: $chain"}
    chain.fsSave()
    val genesis = Block (
        Immut (
            0,
            Payload( false, ""),
            null,
            null,
            emptyArray()
        ),
        chain.getGenesis(),
        null
    )
    chain.fsSaveBlock(genesis)
    chain.fsSavePay(genesis.hash, "")
    return file.readText().fromJsonToChain()
}

fun Host.chainsLeave (name: String) : Boolean {
    val chain = Chain(this.root,name).validate()
    val file = File(chain.path())
    return file.exists() && file.deleteRecursively()
}

fun Host.chainsList () : List<String> {
    val file = File(this.root + "/chains/")
    return file.list().let {
        if (it == null) {
            emptyList()
        } else {
            it.map { it.replace('_', '/') }
        }
    }
}

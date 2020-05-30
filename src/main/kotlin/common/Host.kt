package org.freechains.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import org.freechains.platform.fsRoot
import java.io.File

@Serializable
data class Host (
    val root : String,
    val port : Int
)

// JSON

fun Host.toJson () : String {
    @UseExperimental(UnstableDefault::class)
    val json = Json(JsonConfiguration(prettyPrint=true))
    return json.stringify(Host.serializer(), this)
}

fun String.fromJsonToHost () : Host {
    @UseExperimental(UnstableDefault::class)
    val json = Json(JsonConfiguration(prettyPrint=true))
    return json.parse(Host.serializer(), this)
}

// FILE SYSTEM

fun Host.fsSave () {
    File(this.root + "/host").writeText(this.toJson()+"\n")
}

fun Host_load (dir: String) : Host {
    assert(dir.startsWith("/"))
    return File(fsRoot + "/" + dir + "/host").readText().fromJsonToHost()
}

fun Host_exists (dir: String) : Boolean {
    assert(dir.startsWith("/"))
    return File(fsRoot + "/" + dir).exists()
}

fun Host_create (dir: String, port: Int = PORT_8330) : Host {
    assert(dir.startsWith("/"))
    val root = fsRoot + dir
    val fs = File(root)
    assert(!fs.exists()) { "directory already exists: " + root }
    fs.mkdirs()
    val host = Host(root, port)
    host.fsSave()
    return host
}

// SPLIT

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
    name.nameCheck()
    val name_ = name.replace('/','_')
    val file = File(this.root + "/chains/" + name_ + "/" + "chain")
    return file.readText().fromJsonToChain()
}

fun Host.chainsJoin (name: String, trusted: Boolean, pub: ChainPub?) : Chain {
    name.nameCheck()
    val name_ = name.replace('/','_')
    val chain = Chain(this.root+"/chains/", name, trusted, pub)
    val file = File(chain.root + "/" + name_ + "/" + "chain")
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
    name.nameCheck()
    val name_ = name.replace('/','_')
    val file = File(this.root + "/chains/" + name_ + "/")
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

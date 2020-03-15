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

// CHAIN

fun Host.joinChain (name: String, pub: ChainPub?) : Chain {
    val chain = Chain(this.root+"/chains/", name, pub)
    val file = File(chain.root + chain.name + "/" + "chain")
    assert(!file.exists()) { "chain already exists: $chain"}
    chain.fsSave()
    val genesis = Block (
        Immut (
            0,
            null,
            "",
            false,
            "",
            emptyArray(),
            emptyArray()
        ),
        mutableListOf(),
        null,
        chain.getGenesis()
    )
    chain.fsSaveBlock(genesis)
    return file.readText().fromJsonToChain()
}

fun Host.loadChain (name: String) : Chain {
    val file = File(this.root + "/chains/" + name + "/" + "chain")
    return file.readText().fromJsonToChain()
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

fun Host_create (dir: String, port: Int = 8330) : Host {
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
        0 -> Pair("localhost", 8330)
        1 -> Pair(lst[0], 8330)
        else -> Pair(lst[0], lst[1].toInt())
    }
}

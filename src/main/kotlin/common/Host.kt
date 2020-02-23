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

fun Host.createChain (name: String, ro: Boolean, keys: Array<String>) : Chain {
    val chain = Chain(this.root+"/chains/", name, ro, keys)
    val file = File(chain.root + chain.name + "/" + "chain")
    assert(!file.exists()) { "chain already exists: $chain"}
    chain.save()
    val genesis = Block (
        BlockHashable (
            0,
            null,
            "",
            false,
            "",
            emptyArray(),
            emptyArray()
        ),
        emptyArray(),
        Pair("",""),
        chain.toGenHash()
    )
    chain.saveBlock(genesis)
    return file.readText().fromJsonToChain()
}

fun Host.loadChain (name: String) : Chain {
    val file = File(this.root + "/chains/" + name + "/" + "chain")
    return file.readText().fromJsonToChain()
}

// FILE SYSTEM

fun Host.save () {
    File(this.root + "/host").writeText(this.toJson()+"\n")
}

fun Host_load (dir: String) : Host {
    assert(dir.substring(0,1) == "/")
    return File(fsRoot + "/" + dir + "/host").readText().fromJsonToHost()
}

fun Host_exists (dir: String) : Boolean {
    assert(dir.substring(0,1) == "/")
    return File(fsRoot + "/" + dir).exists()
}

fun Host_create (dir: String, port: Int = 8330) : Host {
    assert(dir.substring(0,1) == "/")
    val root = fsRoot + "/" + dir
    val fs = File(root)
    assert(!fs.exists()) { "directory already exists" }
    fs.mkdirs()
    val host = Host(root, port)
    host.save()
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

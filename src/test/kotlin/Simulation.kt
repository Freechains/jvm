import org.freechains.common.*
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.Integer.max
import java.lang.Integer.min
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.random.asJavaRandom

fun Int.toHost () : String {
    return "--host=localhost:" + this
}

fun normal (v: Pair<Int,Int>) : Int {
    val r = Random.asJavaRandom().nextGaussian()
    return max(1, v.first + (v.second*r).toInt())
}



val ES = arrayOf (
    Pair(0,1), Pair(1,2), Pair(2,3), Pair(3,4), Pair(4,5), Pair(5,6), Pair(6,7), Pair(7,8), Pair(8,9),
    Pair(1,10), Pair(10,11), Pair(11,1),
    Pair(5,12), Pair(12,13), Pair(13,14), Pair(14,15), Pair(15,6),
    Pair(5,16), Pair(16,17), Pair(17,18), Pair(6,18), Pair(18,19), Pair(19,20), Pair(20,7)
)

const val N = 21

val VS = mutableListOf<List<Int>>()
val TODO = mutableListOf<MutableList<Int>>()

class Simulation {
    init {
        for (i in 0 until N) {
            VS.add (
                ES.map {
                    when {
                        (it.first  == i) -> listOf(it.second)
                        (it.second == i) -> listOf(it.first)
                        else             -> emptyList()
                    }
                }.flatten()
            )
            TODO.add(VS[i].toMutableList())
        }
    }

    fun stop_delete () {
        /*
        for (i in 0..N) {
            val h = 8400 + i
            main(arrayOf(i.toHost(), "host", "stop"))
        }
        */
        assert(File("/tmp/freechains/sim/").deleteRecursively())
    }

    fun create_start () {
        for (i in 0 until N) {
            val h = 8400 + i
            main(arrayOf("host", "create", "/tmp/freechains/sim/$h/", "$h"))
            thread {
                main(arrayOf("host", "start", "/tmp/freechains/sim/$h/"))
            }
        }
    }

    fun join (chain: String) {
        for (i in 0 until N) {
            val h = 8400 + i
            main(arrayOf(h.toHost(), "chain", "join", chain, "trusted"))
        }
    }

    fun listen (chain: String, f: (Int)->Unit) {
        for (i in 0 until N) {
            val h = 8400 + i
            thread {
                val socket = Socket("localhost", h)
                val writer = DataOutputStream(socket.getOutputStream()!!)
                val reader = DataInputStream(socket.getInputStream()!!)
                writer.writeLineX("$PRE chain listen")
                writer.writeLineX(chain)
                while (true) {
                    val n = reader.readLineX().toInt()
                    if (n > 0) {
                        f(i)
                    }
                }

            }
        }
    }

    fun post (h: Int, chain: String, txt: String) {
        while (true) {
            try {
                main_(arrayOf(h.toHost(), "chain", "post", chain, "inline", txt))
            } catch (t: Throwable) {
                continue
            }
            break
        }
    }

    fun handle (i: Int, chain: String, latency: Pair<Int,Int>) {
        var doing : List<Int>
        synchronized (TODO[i]) {
            doing = TODO[i].toList()
            TODO[i].clear()
        }
        thread {
            val h = 8400 + i
            val peers = doing.shuffled()
            for (p in peers) {
                Thread.sleep(normal(latency).toLong())
                main_(arrayOf(h.toHost(), "chain", "send", chain, "localhost:${8400+p}"))
                synchronized (TODO[i]) {
                    TODO[i].add(p)
                }
            }
        }
    }

    @Test
    fun sim_chat () {
        val CHAIN = "/chat"
        val TOTAL  = 10*min   // simulation time
        val PERIOD = Pair(20*sec.toInt(), 15*sec.toInt())   // period between two messages
        val LATENCY= Pair(250*ms.toInt(), 50*ms.toInt())   // network latency (start time)

        val LEN_50 = Pair(50,10)      // message length
        val LEN_05 = Pair(5,2)        // message length

        stop_delete()
        create_start()
        Thread.sleep(2*sec)
        join(CHAIN)
        listen(CHAIN, { i ->  handle(i,CHAIN, LATENCY)})
        Thread.sleep(2*sec)

        main_(arrayOf(8400.toHost(), "chain", "post", CHAIN, "inline", "first message"))
        Thread.sleep(20*sec)

        val start = getNow()
        var now = getNow()
        var i = 1
        while (now < start+TOTAL) {
            Thread.sleep(normal(PERIOD).toLong())

            val h = 8400 + (0 until N).random()
            val txt = when ((1..2).random()) {
                1    -> "#$i - @$h: ${"x".repeat(normal(LEN_50))}"
                else -> "x".repeat(normal(LEN_05))
            }
            println(">>> h = $h")
            main_(arrayOf(h.toHost(), "chain", "post", CHAIN, "inline", txt))

            now = getNow()
            i += 1
        }

        Thread.sleep(1*min)
        println("PARAMS: n=$N, total=$TOTAL, period=$PERIOD)")
        println("        m50=$LEN_50, m05=$LEN_05, latency=$LATENCY")
    }

    @Test
    fun xxx () {
        stop_delete()
        main(arrayOf("host", "create", "/tmp/freechains/sim/xxx/", "8400"))
        thread {
            main(arrayOf("host", "start", "/tmp/freechains/sim/xxx/"))
        }
        Thread.sleep(2*sec)
        main(arrayOf(8400.toHost(), "chain", "join", "/", "trusted"))

        while (true) {
            var LEN = 10000000
            while (LEN > 0) {
                val len = min(127500, LEN)
                println(">>> len = $len / $LEN")
                LEN -= len
                val txt = "${"x".repeat(len)}"
                main_(arrayOf(8400.toHost(), "chain", "post", "/", "inline", txt))
            }
            Thread.sleep(1000)
        }
    }

    @Test
    fun sim_insta () {
        val CHAIN = "/insta"
        val TOTAL  = 10*min   // simulation time
        val LATENCY= Pair(250*ms.toInt(), 50*ms.toInt())   // network latency (start time)

        stop_delete()
        create_start()
        Thread.sleep(2*sec)
        join(CHAIN)
        listen(CHAIN, { i ->  handle(i,CHAIN, LATENCY)})
        Thread.sleep(2*sec)

        main_(arrayOf(8400.toHost(), "chain", "post", CHAIN, "inline", "first message"))
        Thread.sleep(20*sec)

        val _day  = 10*min
        val _hour = _day  / 24
        val _min  = _hour / 60
        val _sec  = _min  / 60

        val start = getNow()
        var now: Long
        var i = 0

        fun running () : Boolean {
            var still = true
            synchronized (this) {
                now = getNow()
                if (now >= start+TOTAL) {
                    still = false
                }
                i += 1
            }
            return still
        }

        val t1 = thread {
            val HOSTS = arrayOf(11,14)
            val PERIOD = Pair(5*_hour.toInt(), 2*_hour.toInt())
            val LENGTH = Pair(5*1000*1000, 2*1000*1000)

            while (running()) {
                Thread.sleep(normal(PERIOD).toLong())
                val h = 8400 + HOSTS[(0..1).random()]
                var LEN = normal(LENGTH)
                println(">>> H = $h")
                while (LEN > 0) {
                    val len = min(127500, LEN)
                    //println(">>> len = $len / $LEN")
                    LEN -= len
                    val txt = "#$i - @$h: ${"x".repeat(len)}"
                    post(h, CHAIN, txt)
                }
            }
            println("AUTHOR: period=$PERIOD, len=$LENGTH")
        }
        val t2 = thread {
            val PERIOD = Pair(5*_min.toInt(), 3*_min.toInt())
            val LENGTH = Pair(50, 20)

            while (running()) {
                Thread.sleep(normal(PERIOD).toLong())
                val h = 8400 + (0 until N).random()
                val txt = "#$i - @$h: ${"x".repeat(normal(LENGTH))}"
                println(">>> h = $h")
                post(h, CHAIN, txt)
            }
            println("VIEWER: period=$PERIOD, len=$LENGTH")
        }
        //t1.join()
        t2.join()
        Thread.sleep(1*min)
        println("PARAMS: n=$N, total=$TOTAL, latency=$LATENCY, _day=$_day")
    }
}
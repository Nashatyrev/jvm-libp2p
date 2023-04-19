package io.libp2p.simulate.main.tcp

import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.mbitsPerSecond
import io.libp2p.tools.log
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    val tcConfig = TcConfig(justPrint = true)
    tcConfig.setLimits("lo", 8888, Bandwidth(10 * 1024 * 1024 / 8), 50.milliseconds)
}

class TcConfig(
    val logger: (String) -> Unit = { log("[TcConfig] $it") },
    val justPrint: Boolean = false
) {

    val tcconfigExeDir = "/usr/local/bin"
    val tcset = "$tcconfigExeDir/tcset"
    val tcdel = "$tcconfigExeDir/tcdel"

    fun setLimits(ifc: String, port: Int, bandwidth: Bandwidth, delay: Duration) {
        logger("Clearing config for $ifc")
        exec("$tcdel $ifc--all")
        logger("Setting limits for $ifc: bandwidth: $bandwidth, delay: $delay")

        listOf("outgoing", "incoming").forEach { direction ->

            val portOption = when(direction) {
                "outgoing" -> "--src-port"
                "incoming" -> "--dst-port"
                else -> throw IllegalStateException()
            }

            exec(
                listOf(
                    tcset,
                    ifc,
                    "--rate",
                    "${bandwidth.bytesPerSecond * 8}bps",
                    "--delay",
                    delay.inWholeMilliseconds.toString(),
                    portOption,
                    "$port",
                    "--direction",
                    direction
                    )
            )
        }
        logger("Done.")
    }


    fun exec(args: String) {
        exec(args.split(" "))
    }

    fun exec(args: List<String>) {
        logger("> ${args.joinToString(" ")}")
        if (!justPrint) {
            ProcessBuilder()
                .command(args)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .start()
                .waitFor(5, TimeUnit.SECONDS)
        }
    }
}
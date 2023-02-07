package io.libp2p.tools

import java.text.SimpleDateFormat
import java.util.*

private val LOG_TIME_FORMAT = SimpleDateFormat("hh:mm:ss.SSS")

fun log(s: String) {
    println("[${LOG_TIME_FORMAT.format(Date())}] $s")
}

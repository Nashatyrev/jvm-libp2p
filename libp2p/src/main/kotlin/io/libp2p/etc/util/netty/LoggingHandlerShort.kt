package io.libp2p.etc.util.netty

import io.netty.channel.ChannelHandlerContext
import io.netty.handler.logging.ByteBufFormat
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler

/**
 * [LoggingHandler] limiting message size
 */
class LoggingHandlerShort : LoggingHandler {

    constructor() : super()
    constructor(format: ByteBufFormat?) : super(format)
    constructor(level: LogLevel?) : super(level)
    constructor(level: LogLevel?, byteBufFormat: ByteBufFormat?) : super(level, byteBufFormat)
    constructor(clazz: Class<*>?) : super(clazz)
    constructor(clazz: Class<*>?, level: LogLevel?) : super(clazz, level)
    constructor(clazz: Class<*>?, level: LogLevel?, byteBufFormat: ByteBufFormat?) : super(clazz, level, byteBufFormat)
    constructor(name: String?) : super(name)
    constructor(name: String?, level: LogLevel?) : super(name, level)
    constructor(name: String?, level: LogLevel?, byteBufFormat: ByteBufFormat?) : super(name, level, byteBufFormat)

    var maxHeadingLines: Int = 50
    var maxTrailingLines: Int = 10
    var linesCutThreshold = 30
    var maxLineHeadingChars: Int = 1024
    var maxLineTrailingChars: Int = 128
    var charsCutThreshold = 256
    var skipRead = false
    var skipFlush = false

    override fun format(ctx: ChannelHandlerContext?, eventName: String?, arg: Any?): String {
        val orig = super.format(ctx, eventName, arg)
        val lines = orig.lines()
        val extraLines = lines.size - (maxHeadingLines + maxTrailingLines)

        val lessLines =
            if (extraLines > linesCutThreshold) {
                lines.take(maxHeadingLines) +
                    "" +
                    "............. more $extraLines lines ............." +
                    "" +
                    lines.takeLast(maxTrailingLines)
            } else {
                lines
            }
        val shortLines = lessLines.map {
            val extraChars = it.length - (maxLineHeadingChars + maxLineTrailingChars)
            if (extraChars > charsCutThreshold) {
                it.take(maxLineHeadingChars) +
                    " ... more $extraChars chars ... " +
                    it.takeLast(maxLineTrailingChars)
            } else {
                it
            }
        }
        return shortLines.joinToString("\n")
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (skipRead) {
            ctx.fireChannelRead(msg)
        } else {
            super.channelRead(ctx, msg)
        }
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        if (skipRead) {
            ctx.fireChannelReadComplete()
        } else {
            super.channelReadComplete(ctx)
        }
    }

    override fun flush(ctx: ChannelHandlerContext) {
        if (skipFlush) {
            ctx.flush()
        } else {
            super.flush(ctx)
        }
    }
}

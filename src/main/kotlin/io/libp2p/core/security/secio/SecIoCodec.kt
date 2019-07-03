package io.libp2p.core.security.secio

import io.libp2p.core.types.toByteArray
import io.libp2p.core.types.toByteBuf
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageCodec
import org.bouncycastle.crypto.macs.HMac
import java.nio.ByteBuffer
import java.util.*
import javax.crypto.Cipher

class SecIoCodec(val local: SecioParams, val remote: SecioParams) : MessageToMessageCodec<ByteBuf, ByteBuf>() {

    override fun encode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        val dataArr = ByteArray(msg.readableBytes())
        val dataBuf = ByteBuffer.wrap(dataArr)

        local.cipher.doFinal(msg.nioBuffer(), dataBuf)
        local.mac.reset()
        local.mac.update(dataArr, 0, dataArr.size)
        val macArr = ByteArray(local.mac.macSize)
        local.mac.doFinal(macArr, 0)
        out.add(
            Unpooled.wrappedBuffer(
                Unpooled.wrappedBuffer(dataArr),
                Unpooled.wrappedBuffer(macArr)
            )
        )
    }

    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        val macBytes = msg.toByteArray(from = msg.readableBytes() - remote.mac.macSize)
        val cipherBytes = msg.toByteArray(to = msg.readableBytes() - remote.mac.macSize)
        remote.mac.reset()
        remote.mac.update(cipherBytes, 0, cipherBytes.size)
        val macArr = ByteArray(remote.mac.macSize)
        remote.mac.doFinal(macArr, 0)
        if (!macBytes.contentEquals(macArr)) throw MacMismatch()
        val plainData = remote.cipher.doFinal(cipherBytes).toByteBuf()
        out.add(plainData)
    }


    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        cause.printStackTrace()
        if (cause is SecioError) {
            ctx.channel().close()
        }
    }

    companion object {

        /**
         * This is just a restructure of the aforementioned [decode] function to facilitate testing with [ByteArray]s.
         * @param useDoFinal whether to solely use [Cipher.doFinal] or to use [Cipher.update] combined with [Cipher.doFinal].
         */
        fun decodeByteArray(
            encodedData: ByteArray,
            macSize: Int,
            hmac: HMac,
            cipher: Cipher,
            useDoFinal: Boolean
        ): ByteArray {
            var numBytes = encodedData.size // 48
            var cipherBytesLength = numBytes - macSize // 16
            var macBytes = encodedData.slice(IntRange(cipherBytesLength, numBytes - 1)).toByteArray()
            var cipherBytes = encodedData.slice(IntRange(0, cipherBytesLength - 1)).toByteArray()

            hmac.reset()
            hmac.update(cipherBytes, 0, cipherBytes.size)
            var macArr = ByteArray(macSize)
            hmac.doFinal(macArr, 0)
            if (!macBytes.contentEquals(macArr)) throw MacMismatch()

            // Now decrypt!
            val expectedByteCount = cipher.getOutputSize(cipherBytes.size)
            val decryptedResult = cipher.update(cipherBytes)

            if (decryptedResult.size < expectedByteCount) {
                var finalBytes = cipher.doFinal()
                if (finalBytes != null && finalBytes.isNotEmpty()) {
                    return decryptedResult + finalBytes
                }
            }
            return decryptedResult
        }
    }
}
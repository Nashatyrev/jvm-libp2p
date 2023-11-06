package io.libp2p.simulate.pubsub.erasure.router

import io.libp2p.pubsub.erasure.ErasureCoder
import io.libp2p.pubsub.erasure.ErasureRouter
import io.libp2p.pubsub.erasure.router.MessageRouterFactory
import io.netty.channel.ChannelHandler
import java.util.concurrent.ExecutorService

class SimErasureRouter(
    executor: ExecutorService,
    erasureCoder: ErasureCoder,
    messageRouterFactory: MessageRouterFactory
) : ErasureRouter(executor, erasureCoder, messageRouterFactory) {

    override fun initChannelWithHandler(streamHandler: StreamHandler, handler: ChannelHandler?) {
//        if (serializeToBytes) {
//            super.initChannelWithHandler(streamHandler, handler)
//        } else {
            // exchange Rpc.RPC messages directly (without serialization) for performance reasons
            with(streamHandler.stream) {
                handler?.also { pushHandler(it) }
                pushHandler(streamHandler)
            }
//        }
    }

}
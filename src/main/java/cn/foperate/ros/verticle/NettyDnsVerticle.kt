package cn.foperate.ros.verticle

import cn.foperate.ros.netty.*
import cn.foperate.ros.pac.DomainUtil
import com.github.benmanes.caffeine.cache.Caffeine
import io.netty.buffer.Unpooled
import io.netty.handler.codec.dns.*
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.eventbus.EventBus
import io.vertx.core.impl.VertxInternal
import io.vertx.kotlin.core.dns.dnsClientOptionsOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/*****
 * 进行DNS过滤、解析和转发，并请求将结果保存到ROS中。
 * 改用Kotlin协程来实现，期望语义上更加简洁清晰。
 * @author Aston Mei
 * @since 2021-07-25
 */
class NettyDnsVerticle : CoroutineVerticle() {
    private lateinit var backupClient: DnsProxy
    private lateinit var proxyClient: DnsProxy
    private lateinit var dnsServer: DnsServer
    private val aCache = Caffeine.newBuilder()
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .maximumSize(1000)
        .build<String, Future<List<DnsRawRecord>>>()

    private var localPort: Int = 53  // DNS服务监听的端口
    private lateinit var remote: String  // upstream服务器地址
    private var remotePort: Int = 53  // upstream服务器端口
    private lateinit var fallback: String
    private lateinit var blockAddress: InetAddress

    private lateinit var eb: EventBus

    /*private var metrics: DatagramSocketMetrics? = null
    private lateinit var channel: DatagramChannel
    private lateinit var actualCtx: ContextInternal*/

    override suspend fun start() {
        try {
            localPort = config.getInteger("localPort", localPort)
            remote = config.getString("remote")
            remotePort = config.getInteger("remotePort", remotePort)
            fallback = config.getString("fallback")
            val block = config.getString("blockAddress")
            // 实际不会发生阻塞
            blockAddress = InetAddress.getByName(block)

            eb = vertx.eventBus()
            backupClient = DnsProxyImpl(
                vertx as VertxInternal, dnsClientOptionsOf(
                    host = fallback,
                    port = 53,
                    recursionDesired = true
                )
            )

            proxyClient = DnsProxyImpl(
                vertx as VertxInternal, dnsClientOptionsOf(
                    host = remote,
                    port = remotePort,
                    recursionDesired = true
                )
            )

            /*setupServer(
                dnsServerOptionsOf(
                    port = localPort,
                    host = "0.0.0.0"
                )
            )*/
            dnsServer = DnsServerImpl.create(vertx as VertxInternal, dnsServerOptionsOf(
                port = localPort,
                host = "0.0.0.0"
            )).handler {
                val question = it.recordAt<DnsQuestion>(DnsSection.QUESTION)
                val response = DatagramDnsResponse(it.recipient(), it.sender(), it.id())
                handlePacket(question, response)
            }
            dnsServer.listen(localPort, "0.0.0.0").await()
            log.debug("UDP服务已经启动")
        } catch (e: Exception) {
            log.error(e.message, e)
        }
    }

    override suspend fun stop() {
        // make sure everything is flushed out on close
        /*if (!channel.isOpen) {
            return
        }
        channel.flush()
        channel.close().addListener(actualCtx.promise())*/
        dnsServer.close()
    }

    /*fun setupServer(options: DnsServerOptions) {
        vertx.createDatagramSocket()
        val internal = vertx as VertxInternal
        requireNotNull(options.host) {
            "no null host accepted"
        }
        //this.options = options
        //val creatingContext = internal.context
        val dnsServer = InetSocketAddress(options.host, options.port)
        require(!dnsServer.isUnresolved) { "Cannot resolve the host to a valid ip address" }
        val transport = internal.transport()
        channel =
            transport.datagramChannel(if (dnsServer.address is Inet4Address) InternetProtocolFamily.IPv4 else InternetProtocolFamily.IPv6)
        transport.configure(channel, DatagramSocketOptions(options))

        actualCtx = internal.orCreateContext
        channel.config().setOption(ChannelOption.DATAGRAM_CHANNEL_ACTIVE_ON_REGISTRATION, true)
        //channel.config().setOption(ChannelOption.SO_BROADCAST, true)
        val bufAllocator = channel.config().getRecvByteBufAllocator<MaxMessagesRecvByteBufAllocator>()
        bufAllocator.maxMessagesPerRead(1)

        // FIXME  也许没有用
        channel.config().allocator = PartialPooledByteBufAllocator.INSTANCE

        actualCtx.nettyEventLoop().register(channel)
        if (options.logActivity) {
            channel.pipeline().addLast("logging", LoggingHandler())
        }
        val metrics = internal.metricsSPI()
        this.metrics = metrics?.createDatagramSocketMetrics(options)

        channel.pipeline().addLast(DatagramDnsQueryDecoder())
        channel.pipeline().addLast(DatagramDnsResponseEncoder())


        channel.pipeline().addLast("handler", VertxHandler.create { ctx -> Connection(actualCtx, ctx, this::handlePacket)} )
        listen(options.port, options.host)
        /*channel.pipeline().addLast(object : SimpleChannelInboundHandler<DatagramDnsQuery>(
        ) {
            override fun channelRead0(ctx: ChannelHandlerContext, query: DatagramDnsQuery) {
                val response = DatagramDnsResponse(query.recipient(), query.sender(), query.id())
                try {
                    val dnsQuestion = query.recordAt<DnsQuestion>(DnsSection.QUESTION)
                    val questionName = dnsQuestion.name()
                    response.addRecord(DnsSection.QUESTION, dnsQuestion)
                    log.debug("查询的域名：$dnsQuestion")
                    when {
                        DomainUtil.match(questionName) -> {
                            log.debug("gfwlist hint")
                            //forwardToRemote(request, questionName, questionType)

                            val future = aCache.get(questionName) {
                                val promise = Promise.promise<List<DnsRawRecord>>()
                                proxyClient.proxy(dnsQuestion).onSuccess {
                                    //log.debug(it.toString())
                                    /*val list = it.map { raw ->
                                        DnsAnswer.fromRawRecord(raw)
                                    }*/
                                    if (it.isEmpty()) {
                                        aCache.invalidate(questionName)
                                    }
                                    promise.tryComplete(it)
                                }.onFailure {
                                    // 失败的请求，从缓存中去掉该key
                                    aCache.invalidate(questionName)
                                    promise.tryFail(it)
                                }
                                promise.future()
                            }
                            future!!.onSuccess {
                                val aRecordIps = mutableListOf<String>()
                                for (answer in it) {
                                    response.addRecord(DnsSection.ANSWER, answer.copy())
                                    if (answer.type()==DnsRecordType.A) {
                                        //response.addRecord(DnsSection.ANSWER, answer.toRawRecord())
                                        val content = answer.content()
                                        val address = content.getUnsignedByte(0).toString() + "." +
                                            content.getUnsignedByte(1).toString() + "." +
                                            content.getUnsignedByte(2).toString() + "." +
                                                content.getUnsignedByte(3).toString()
                                        /*val address = content[0].toUByte().toString() + "." +
                                                content[1].toUByte().toString() + "." +
                                                content[2].toUByte().toString() + "." +
                                                content[3].toUByte().toString()*/
                                        log.debug(address)
                                        aRecordIps.add(address)
                                    }
                                }
                                ctx.writeAndFlush(response)
                                if (aRecordIps.isNotEmpty()) {
                                    eb.request<Long>(
                                        RosVerticle.EVENT_ADDRESS, jsonObjectOf(
                                            "domain" to questionName,
                                            "address" to aRecordIps
                                        )
                                    ).onSuccess {
                                        log.debug("call success")
                                    }.onFailure { err ->
                                        log.error(err.message)
                                    }
                                }
                            }.onFailure {
                                // 但是请求失败后，会从备用服务器解析结果
                                backupClient.proxy(dnsQuestion).onSuccess {
                                    log.debug(it.toString())
                                    for (answer in it) {
                                        response.addRecord(DnsSection.ANSWER, answer)
                                    }
                                    ctx.writeAndFlush(response)
                                }
                            }
                        }
                        DomainUtil.matchBlock(questionName) -> {
                            log.debug("adBlock matched")
                            //val reply = blockMessage(message)
                            //serverSocket.send(Buffer.buffer(reply), request.sender().port(), request.sender().host())
                            val buf = Unpooled.wrappedBuffer(blockAddress.address)
                            val queryAnswer = DefaultDnsRawRecord(dnsQuestion.name(), DnsRecordType.A, 600, buf)
                            response.addRecord(DnsSection.ANSWER, queryAnswer)
                            ctx.writeAndFlush(response)
                        }
                        else -> {
                            // TODO  对于没有的域名采用迭代方式
                            backupClient.proxy(dnsQuestion).onSuccess {
                                log.debug(it.toString())
                                for (answer in it) {
                                    response.addRecord(DnsSection.ANSWER, answer)
                                }
                                ctx.writeAndFlush(response)
                            }
                        }
                    }
                } catch (e: Exception) {
                    log.error("异常了：$e", e)
                }
            }

            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                cause.printStackTrace()
            }
        })
        channel.bind(InetSocketAddress(options.host, options.port))*/
    }

    fun listen(port: Int, address: String, handler: Handler<AsyncResult<NettyDnsVerticle>>): NettyDnsVerticle {
        Objects.requireNonNull(handler, "no null handler accepted")
        listen(SocketAddress.inetSocketAddress(port, address)).onComplete(handler)
        return this
    }

   fun listen(port: Int, address: String): Future<NettyDnsVerticle> {
        return listen(SocketAddress.inetSocketAddress(port, address))
    }

    fun localAddress(): SocketAddress {
        return actualCtx.owner().transport().convert(channel.localAddress())
    }

    private fun listen(local: SocketAddress): Future<NettyDnsVerticle> {
        val resolver = actualCtx.owner().addressResolver()
        val promise = actualCtx.promise<Void>()
        val f1 = resolver.resolveHostname(actualCtx.nettyEventLoop(), local.host())
        f1.addListener(GenericFutureListener { res1: io.netty.util.concurrent.Future<InetSocketAddress> ->
            if (res1.isSuccess) {
                val f2 = channel.bind(InetSocketAddress(res1.now.address, local.port()))
                f2.addListener(GenericFutureListener { res2: io.netty.util.concurrent.Future<Void> ->
                    if (res2.isSuccess) {
                        metrics?.listening(local.host(), localAddress())
                    }
                })
                f2.addListener(promise)
            } else {
                promise.fail(res1.cause())
            }
        })
        return promise.future().map(this)
    }

    internal inner class Connection(context: ContextInternal, val channel: ChannelHandlerContext,
                                    val handler: BiConsumer<DnsQuestion, DatagramDnsResponse>)
        : ConnectionBase(context, channel) {

        override fun metrics(): NetworkMetrics<*>? {
            return metrics
        }

        override fun handleInterestedOpsChanged() {}
        override fun handleException(t: Throwable) {
            super.handleException(t)
            // FIXME
            log.error(t.message, t)
        }

        override fun handleClosed() {
            super.handleClosed()
            var metrics: DatagramSocketMetrics?
            synchronized(this@NettyDnsVerticle) {
                metrics = this@NettyDnsVerticle.metrics
            }
            metrics?.close()
            // FIXME
        }

        public override fun handleMessage(msg: Any) {
            if (msg is DatagramDnsQuery) {
                val question = msg.recordAt<DnsQuestion>(DnsSection.QUESTION)
                val response = DatagramDnsResponse(msg.recipient(), msg.sender(), msg.id())
                handler.accept(question, response)
            }
        }

        /*fun handlePacket(dnsQuestion: DnsQuestion, response: DatagramDnsResponse) {
            try {
                val questionName = dnsQuestion.name()
                response.addRecord(DnsSection.QUESTION, dnsQuestion)
                log.debug("查询的域名：$dnsQuestion")
                when {
                    DomainUtil.match(questionName) -> {
                        log.debug("gfwlist hint")
                        //forwardToRemote(request, questionName, questionType)

                        val future = aCache.get(questionName) {
                            val promise = Promise.promise<List<DnsRawRecord>>()
                            proxyClient.proxy(dnsQuestion).onSuccess {
                                //log.debug(it.toString())
                                /*val list = it.map { raw ->
                                    DnsAnswer.fromRawRecord(raw)
                                }*/
                                if (it.isEmpty()) {
                                    aCache.invalidate(questionName)
                                }
                                promise.tryComplete(it)
                            }.onFailure {
                                // 失败的请求，从缓存中去掉该key
                                aCache.invalidate(questionName)
                                promise.tryFail(it)
                            }
                            promise.future()
                        }
                        future!!.onSuccess {
                            val aRecordIps = mutableListOf<String>()
                            for (answer in it) {
                                response.addRecord(DnsSection.ANSWER, answer.copy())
                                if (answer.type()==DnsRecordType.A) {
                                    //response.addRecord(DnsSection.ANSWER, answer.toRawRecord())
                                    val content = answer.content()
                                    val address = content.getUnsignedByte(0).toString() + "." +
                                            content.getUnsignedByte(1).toString() + "." +
                                            content.getUnsignedByte(2).toString() + "." +
                                            content.getUnsignedByte(3).toString()
                                    /*val address = content[0].toUByte().toString() + "." +
                                            content[1].toUByte().toString() + "." +
                                            content[2].toUByte().toString() + "." +
                                            content[3].toUByte().toString()*/
                                    log.debug(address)
                                    aRecordIps.add(address)
                                }
                            }
                            channel.writeAndFlush(response)
                            if (aRecordIps.isNotEmpty()) {
                                eb.request<Long>(
                                    RosVerticle.EVENT_ADDRESS, jsonObjectOf(
                                        "domain" to questionName,
                                        "address" to aRecordIps
                                    )
                                ).onSuccess {
                                    log.debug("call success")
                                }.onFailure { err ->
                                    log.error(err.message)
                                }
                            }
                        }.onFailure {
                            // 但是请求失败后，会从备用服务器解析结果
                            backupClient.proxy(dnsQuestion).onSuccess {
                                log.debug(it.toString())
                                for (answer in it) {
                                    response.addRecord(DnsSection.ANSWER, answer)
                                }
                                channel.writeAndFlush(response)
                            }
                        }
                    }
                    DomainUtil.matchBlock(questionName) -> {
                        log.debug("adBlock matched")
                        //val reply = blockMessage(message)
                        //serverSocket.send(Buffer.buffer(reply), request.sender().port(), request.sender().host())
                        val buf = Unpooled.wrappedBuffer(blockAddress.address)
                        val queryAnswer = DefaultDnsRawRecord(dnsQuestion.name(), DnsRecordType.A, 600, buf)
                        response.addRecord(DnsSection.ANSWER, queryAnswer)
                        channel.writeAndFlush(response)
                    }
                    else -> {
                        // TODO  对于没有的域名采用迭代方式
                        backupClient.proxy(dnsQuestion).onSuccess {
                            log.debug(it.toString())
                            for (answer in it) {
                                response.addRecord(DnsSection.ANSWER, answer)
                            }
                            channel.writeAndFlush(response)
                        }
                    }
                }
            } catch (e: Exception) {
                log.error("异常了：$e", e)
            }
        }*/
    }*/

    fun handlePacket(dnsQuestion: DnsQuestion, response: DatagramDnsResponse) {
        try {
            val questionName = dnsQuestion.name()
            response.addRecord(DnsSection.QUESTION, dnsQuestion)
            log.debug("查询的域名：$dnsQuestion")
            when {
                DomainUtil.match(questionName) -> {
                    log.debug("gfwlist hint")
                    //forwardToRemote(request, questionName, questionType)

                    val future = aCache.get(questionName) {
                        val promise = Promise.promise<List<DnsRawRecord>>()
                        proxyClient.proxy(dnsQuestion).onSuccess {
                            //log.debug(it.toString())
                            /*val list = it.map { raw ->
                                DnsAnswer.fromRawRecord(raw)
                            }*/
                            if (it.isEmpty()) {
                                aCache.invalidate(questionName)
                            }
                            promise.tryComplete(it)
                        }.onFailure {
                            // 失败的请求，从缓存中去掉该key
                            aCache.invalidate(questionName)
                            promise.tryFail(it)
                        }
                        promise.future()
                    }
                    future!!.onSuccess {
                        val aRecordIps = mutableListOf<String>()
                        for (answer in it) {
                            response.addRecord(DnsSection.ANSWER, answer.copy())
                            if (answer.type()==DnsRecordType.A) {
                                //response.addRecord(DnsSection.ANSWER, answer.toRawRecord())
                                val content = answer.content()
                                val address = content.getUnsignedByte(0).toString() + "." +
                                        content.getUnsignedByte(1).toString() + "." +
                                        content.getUnsignedByte(2).toString() + "." +
                                        content.getUnsignedByte(3).toString()
                                /*val address = content[0].toUByte().toString() + "." +
                                        content[1].toUByte().toString() + "." +
                                        content[2].toUByte().toString() + "." +
                                        content[3].toUByte().toString()*/
                                log.debug(address)
                                aRecordIps.add(address)
                            }
                        }
                        dnsServer.send(response)
                        if (aRecordIps.isNotEmpty()) {
                            eb.request<Long>(
                                RosVerticle.EVENT_ADDRESS, jsonObjectOf(
                                    "domain" to questionName,
                                    "address" to aRecordIps
                                )
                            ).onSuccess {
                                log.debug("call success")
                            }.onFailure { err ->
                                log.error(err.message)
                            }
                        }
                    }.onFailure {
                        // 但是请求失败后，会从备用服务器解析结果
                        backupClient.proxy(dnsQuestion).onSuccess {
                            log.debug(it.toString())
                            for (answer in it) {
                                response.addRecord(DnsSection.ANSWER, answer)
                            }
                            dnsServer.send(response)
                        }
                    }
                }
                DomainUtil.matchBlock(questionName) -> {
                    log.debug("adBlock matched")
                    //val reply = blockMessage(message)
                    //serverSocket.send(Buffer.buffer(reply), request.sender().port(), request.sender().host())
                    val buf = Unpooled.wrappedBuffer(blockAddress.address)
                    val queryAnswer = DefaultDnsRawRecord(dnsQuestion.name(), DnsRecordType.A, 600, buf)
                    response.addRecord(DnsSection.ANSWER, queryAnswer)
                    dnsServer.send(response)
                }
                else -> {
                    // TODO  对于没有的域名采用迭代方式
                    backupClient.proxy(dnsQuestion).onSuccess {
                        log.debug(it.toString())
                        for (answer in it) {
                            response.addRecord(DnsSection.ANSWER, answer)
                        }
                        dnsServer.send(response)
                    }
                }
            }
        } catch (e: Exception) {
            log.error("异常了：$e", e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(NettyDnsVerticle::class.java)
    }
}
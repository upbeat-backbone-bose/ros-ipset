package cn.foperate.ros.netty

import io.netty.handler.codec.dns.DnsQuestion
import io.netty.handler.codec.dns.DnsRawRecord
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.dns.DnsClientOptions
import io.vertx.core.impl.VertxInternal

interface DnsProxy {
    fun proxy(question: DnsQuestion): Future<List<DnsRawRecord>>
    fun connect(localPort: Int): DnsProxy
    fun close(): Future<Void>
    companion object {
        fun create(vertx: Vertx, options: DnsClientOptions, localPort: Int): DnsProxy {
            return DnsProxyImpl(vertx as VertxInternal, options).connect(localPort)
        }
    }
}

// 接口支持两种不同的调用方式，与vertx标准调用方式类似
fun Vertx.createDnsProxy(options: DnsClientOptions, localPort: Int)
    = DnsProxyImpl(this as VertxInternal, options).connect(localPort)
package cn.foperate.ros

import cn.foperate.ros.pac.DomainUtil
import cn.foperate.ros.verticle.DnsVeticle
import cn.foperate.ros.verticle.RosVerticle
import io.vertx.core.VertxOptions
import io.vertx.core.logging.SLF4JLogDelegateFactory
import io.vertx.kotlin.core.deploymentOptionsOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.mutiny.core.Vertx
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import tech.stacktrace.jrodns.pac.GFWList
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.*

object IPset {
    private val logger = LoggerFactory.getLogger(IPset::class.java)

    private lateinit var gfwlistPath: String
    private var rosUser: String? = null
    private var rosPwd: String? = null
    private var rosIp: String? = null
    private lateinit var rosFwadrKey: String
    private var rosIdle: Int? = null
    private var maxThread = 8
    private var localPort: Int = 53
    private var remote: String? = null
    private var remotePort: Int = 53

    lateinit var excludeHosts: HashSet<String>

    private fun checkFile(path: String): File {
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            throw RuntimeException("$path not found")
        }
        return file
    }

    @Throws(IOException::class)
    private fun readProp(configFilePath: String) {
        val hosts = mutableListOf<String>()
        val file = checkFile(configFilePath)
        val properties = Properties()
        properties.load(FileInputStream(file))
        gfwlistPath = properties.getProperty("gfwlistPath", "gfwlist.txt")
        rosUser = properties.getProperty("rosUser")
        rosPwd = properties.getProperty("rosPwd")
        rosIp = properties.getProperty("rosIp")
        rosFwadrKey = properties.getProperty("rosFwadrKey")
        remote = properties.getProperty("remote")
        val tmp = properties.getProperty("excludeHosts")
        if (StringUtils.isNotBlank(tmp)) {
            hosts.addAll(
                tmp.split(",")
                    .map(String::trim)
                    .filter(String::isNotBlank)
            )
        }
        excludeHosts = hosts.toHashSet()
        val maxThreadStr = properties.getProperty("maxThread")
        if (maxThreadStr.isNotBlank()) {
            maxThread = Integer.valueOf(maxThreadStr)
        }

        val localPortStr = properties.getProperty("localPort")
        if (localPortStr.isNotBlank()) {
            localPort = Integer.valueOf(localPortStr)
        }

        val remotePortStr = properties.getProperty("remotePort")
        if (remotePortStr.isNotBlank()) {
            remotePort = Integer.valueOf(remotePortStr)
        }

        val rosIdleStr = properties.getProperty("rosIdle")
        rosIdle = if (rosIdleStr.isNotBlank()) {
            Integer.valueOf(rosIdleStr)
        } else {
            30
        }
        if (listOf(
                gfwlistPath,
                rosIp,
                rosUser,
                rosPwd,
                rosFwadrKey,
                rosIdle,
                maxThread,
                localPort,
                remote,
                remotePort
            ).contains(null)
        ) {
            throw RuntimeException("config error")
        }
    }

    @Throws(IOException::class)
    private fun readGFWlist(): String? {
        val file = checkFile(gfwlistPath)
        return FileUtils.readFileToString(file, StandardCharsets.UTF_8)
    }

    @JvmStatic
    fun main(args:Array<String>) {

        System.setProperty("vertx.logger-delegate-factory-class-name",
            SLF4JLogDelegateFactory::class.java.name)
        //InternalLoggerFactory.setDefaultFactory(SLF4JLogDelegateFactory::INSTANCE)
        io.vertx.core.logging.LoggerFactory.initialise()

        var configFilePath = "jrodns.properties"
        if (args.isNotEmpty()) {
            configFilePath = args[0]
        }

        readProp(configFilePath)

        logger.info("config file verify success")

        //RosService.init()

        // RosService.clear();


        // RosService.clear();
        logger.info("RosService init completed")

        //GFWList.loadRules(readGFWlist())
        DomainUtil.loadBlackList(gfwlistPath)

        logger.info("GFWList load completed")

        //val udpServer = UdpServer()
        //udpServer.start()

        logger.info("server started")

        //con.close(); // disconnect from router
//
//        GFWList instacne = GFWList.getInstacne();
//        System.out.println(instacne.match("cdn.ampproject.org."));

        val vertx = Vertx.vertx(VertxOptions().setWorkerPoolSize(maxThread))
        vertx.deployVerticleAndAwait(RosVerticle(), deploymentOptionsOf(
            config = jsonObjectOf(
                "rosFwadrKey" to rosFwadrKey,
                "rosIp" to rosIp,
                "rosUser" to rosUser,
                "rosPwd" to rosPwd,
                "maxThread" to maxThread
            ), worker = true
        ))
        vertx.deployVerticleAndAwait(DnsVeticle(), deploymentOptionsOf(
            config = jsonObjectOf(
                "remotePort" to remotePort,
                "remote" to remote,
                "localPort" to localPort
            )
        ))
    }
}
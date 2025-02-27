package com.criteo.hadoop.garmadon.forwarder;

import com.criteo.hadoop.garmadon.forwarder.channel.ForwarderChannelInitializer;
import com.criteo.hadoop.garmadon.forwarder.kafka.KafkaService;
import com.criteo.hadoop.garmadon.forwarder.metrics.ForwarderEventSender;
import com.criteo.hadoop.garmadon.forwarder.metrics.HostStatistics;
import com.criteo.hadoop.garmadon.forwarder.metrics.PrometheusHttpMetrics;
import com.criteo.hadoop.garmadon.jvm.utils.JavaRuntime;
import com.criteo.hadoop.garmadon.schema.events.Header;
import com.criteo.hadoop.garmadon.schema.events.HeaderUtils;
import com.criteo.hadoop.garmadon.schema.exceptions.TypeMarkerException;
import com.criteo.hadoop.garmadon.schema.serialization.GarmadonSerialization;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public class Forwarder {
    public static final String PRODUCER_PREFIX_NAME = "garmadon.forwarder";
    public static final String CLIENT_ID_NAME = PRODUCER_PREFIX_NAME;

    private static final Logger LOGGER = LoggerFactory.getLogger(Forwarder.class);

    private static final String DEFAULT_FORWARDER_HOST = "localhost";
    private static final String DEFAULT_FORWARDER_PORT = "31000";
    private static final String DEFAULT_PROMETHEUS_PORT = "31001";

    private static String hostname = HeaderUtils.getHostname();

    private final Properties properties;

    private final byte[] header;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    private Channel serverChannel;
    private KafkaService kafkaService;

    //What type of message should be sent to all partitions
    private Set<Integer> broadcastedTypes;

    public Forwarder(Properties properties) {
        this.properties = properties;

        this.broadcastedTypes = Arrays.stream(properties
            .getProperty("broadcasted.message.types", "")
            .split(","))
            .filter(s -> !s.isEmpty())
            .map(typeName -> {
                try {
                    return GarmadonSerialization.getMarker(typeName);
                } catch (TypeMarkerException e) {
                    throw new RuntimeException(e);
                }
            })
            .collect(Collectors.toSet());

        Header.Builder headerBuilder = Header.newBuilder()
                .withId(HeaderUtils.getId())
                .withHostname(hostname)
                .withPid(HeaderUtils.getPid())
                .withUser(HeaderUtils.getUser())
                .withMainClass(HeaderUtils.getJavaMainClass())
                .withJavaVersion(JavaRuntime.version())
                .withJavaFeature(JavaRuntime.feature())
                .addTag(Header.Tag.FORWARDER.name());

        for (String tag : properties.getProperty("forwarder.tags", "").split(",")) {
            headerBuilder.addTag(tag);
        }

        this.header = headerBuilder
                .build()
                .serialize();
    }

    public static String getHostname() {
        return hostname;
    }

    /**
     * Starts netty server for forwarder
     *
     * @return a ChannelFuture that completes when server is started.
     */
    public ChannelFuture run() throws IOException {
        // initialise kafka
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, CLIENT_ID_NAME);
        kafkaService = new KafkaService(properties);

        // initialize metrics
        int prometheusPort = Integer.parseInt(properties.getProperty("prometheus.port", DEFAULT_PROMETHEUS_PORT));
        PrometheusHttpMetrics.start(prometheusPort);
        ForwarderEventSender forwarderEventSender = new ForwarderEventSender(kafkaService, hostname, header);
        HostStatistics.startReport(forwarderEventSender);

        //initialize netty
        String forwarderHost = properties.getProperty("forwarder.host", DEFAULT_FORWARDER_HOST);
        int forwarderPort = Integer.parseInt(properties.getProperty("forwarder.port", DEFAULT_FORWARDER_PORT));
        return startNetty(forwarderHost, forwarderPort);
    }

    /**
     * Closes netty server (in a blocking fashion)
     */
    public void close() {
        LOGGER.info("Shutdown netty server");
        if (serverChannel == null) {
            LOGGER.error("Cannot close a non running server");
        } else {
            serverChannel.close().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
        }

        HostStatistics.stopReport();

        kafkaService.shutdown();

        PrometheusHttpMetrics.stop();
    }

    private ChannelFuture startNetty(String host, int port) {
        int workerThreads = Integer.parseInt(properties.getProperty("forwarder.worker.thread", "1"));

        // Setup netty listener
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(workerThreads);

        //setup boostrap
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                // TODO: Test the Unix Domain Socket implementation will need junixsocket at client side....
                // But should increase perf
                //.channel(EpollServerDomainSocketChannel.class)
                .childHandler(new ForwarderChannelInitializer(kafkaService, broadcastedTypes));

        //start server
        LOGGER.info("Startup netty server");
        ChannelFuture f = b.bind(host, port).addListener(future -> LOGGER.info("Netty server started"));
        serverChannel = f.channel();
        return f;
    }

    public static void main(String[] args) throws Exception {

        // Get properties
        Properties properties = new Properties();
        try (InputStream streamPropFilePath = Forwarder.class.getResourceAsStream("/server.properties")) {
            properties.load(streamPropFilePath);
        }
        //start server and wait for completion (for now we must kill process)
        Forwarder forwarder = new Forwarder(properties);

        // Add ShutdownHook
        Runtime.getRuntime().addShutdownHook(new Thread(forwarder::close));

        try {
            forwarder.run().channel().closeFuture().sync();
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        } finally {
            forwarder.close();
        }
    }
}

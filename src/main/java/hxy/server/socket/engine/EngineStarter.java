package hxy.server.socket.engine;

import hxy.server.socket.configuration.SocketConfiguration;
import hxy.server.socket.entity.SslInfo;
import hxy.server.socket.util.OSInfo;
import hxy.server.socket.util.SpringApplicationContextHolder;
import hxy.server.socket.util.SslFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.util.concurrent.TimeUnit;

/**
 * @ClassName EngineStarter
 * @Description TODO
 * @Author hxy
 * @Date 2020/4/8 18:32
 */
public class EngineStarter {

    private Logger logger = LoggerFactory.getLogger(WebsocketInitHandler.class);

    private static SocketConfiguration config = null;

    public EngineStarter(ApplicationContext ac) {
        SpringApplicationContextHolder.setAc(ac);
        config = SpringApplicationContextHolder.getBean(SocketConfiguration.class);

        HandlerInitizer.chooseMsgHandler();
    }

    EventLoopGroup bossGroup = null;
    EventLoopGroup workGroup = null;

    public void run() {
        ServerBootstrap bootstrap = new ServerBootstrap();

        if (OSInfo.isLinux()) {
            bossGroup = new EpollEventLoopGroup(config.getBossThreadCount(), new DefaultThreadFactory("boss-thread", true));
            workGroup = new EpollEventLoopGroup(config.getWorkThreadCount(), new DefaultThreadFactory("worker-thread", true));
            bootstrap.channel(EpollServerSocketChannel.class)
                    .group(bossGroup, workGroup)
                    .option(EpollChannelOption.TCP_CORK, true);
        } else {
            bossGroup = new NioEventLoopGroup(config.getBossThreadCount(), new DefaultThreadFactory("boss-thread", true));
            workGroup = new NioEventLoopGroup(config.getWorkThreadCount(), new DefaultThreadFactory("worker-thread", true));
            bootstrap.channel(NioServerSocketChannel.class)
                    .group(bossGroup, workGroup);
        }
        //TCP协议中，TCP总是希望每次发送的数据足够大，避免网络中充满了小数据块。
        // Nagle算法就是为了尽可能的发送大数据快。
        // TCP_NODELAY就是控制是否启用Nagle算法。
        // 如果要求高实时性，有数据发送时就马上发送，就将该选项设置为true关闭Nagle算法；
        // 如果要减少发送次数减少网络交互，就设置为false等累积一定大小后再发送。默认为false。
        bootstrap.option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_BACKLOG, 1024);
        logger.info("os:{},Bootstrap configuration: {}", OSInfo.getOSname(), bootstrap.toString());

        try {
            bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel socketChannel) throws Exception {
                    ChannelPipeline pipeline = socketChannel.pipeline();
                    SslInfo sslInfo = config.getSslInfo();
                    // 开启SSL验证
                    if (sslInfo.isOpen()) {
                        pipeline.addLast("ssl", SslFactory.createSslContext(sslInfo.getCertFilePath(), sslInfo.getKeyFilePath()).newHandler(socketChannel.alloc()));
                    }

                    // 设置N秒没有读到数据，则触发一个READER_IDLE事件。
                    pipeline.addLast(new IdleStateHandler(config.getHeartTimeout(), 0, 0, TimeUnit.SECONDS));
                    //选择服务启动
                    SocketInitHandler sc = SpringApplicationContextHolder.getBean("socketInitHandler");
                    sc.buildChannelPipeline(pipeline);

                    pipeline.addLast(new HeartBeatServerHandler());
                }
            });
            // 开始真正绑定端口进行监听
            ChannelFuture future = bootstrap.bind("0.0.0.0", config.getPort()).sync();
            showLog();
            logger.info("================Hxy-socket启动成功， 端口号：{}========",  config.getPort());
            future.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            logger.info("server exit...");
            shutdown();
        }
    }

    public void shutdown(){
        bossGroup.shutdownGracefully();
        workGroup.shutdownGracefully();
    }


    private void showLog() {
        System.out.println("  .      _   _              ______    ____     _____\n" +
                " /\\\\    | \\ | |     /\\     |  ____|  / __ \\   / ____|\n" +
                "( ( )   |  \\| |    /  \\    | |__    | |  | | | (___\n" +
                "( ( )   | . ` |   / /\\ \\   |  __|   | |  | |  \\___ \\\n" +
                " \\\\/    | |\\  |  / ____ \\  | |      | |__| |  ____) |\n" +
                "  '     |_| \\_| /_/    \\_\\ |_|       \\____/  |_____/   version:1.3.0-release    \n" +
                " =============================================================================================");
    }
}
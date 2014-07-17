package ru.carabi.server.eventer;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.DatagramSocket;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NettyListener {
	private static final Logger logger = Logger.getLogger(NettyListener.class.getName());
	private boolean released;
	private final DatagramSocket dsocket = null;
	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;
	
	public boolean isReleased() {
		return released;
	}

	public void start(int port) {
		logger.log(Level.INFO, "starting listener");
		logger.log(Level.INFO, "listening on port {0}", port);
		bossGroup = new NioEventLoopGroup();
		workerGroup = new NioEventLoopGroup();
		try {
			ServerBootstrap b = new ServerBootstrap();
			b.group(bossGroup, workerGroup)
			.channel(NioServerSocketChannel.class)
			.childHandler(new ChannelInitializer<SocketChannel>() {
				@Override
				public void initChannel(SocketChannel ch) throws Exception {
					logger.info("initChannel");
					ch.pipeline().addLast(new MessagesHandler());
				}
			})
			.option(ChannelOption.SO_BACKLOG, 128)
			.childOption(ChannelOption.SO_KEEPALIVE, true);

			// Bind and start to accept incoming connections.
			ChannelFuture f = b.bind(port).sync();

			System.out.println("Started OK");
			// Wait until the server socket is closed.
			f.channel().closeFuture().sync();
		} catch (InterruptedException ex) {
			Logger.getLogger(NettyListener.class.getName()).log(Level.SEVERE, null, ex);
		} finally {
			workerGroup.shutdownGracefully();
			bossGroup.shutdownGracefully();
		}
	}

	public void shutdown() {
		workerGroup.shutdownGracefully();
		bossGroup.shutdownGracefully();
	}
}

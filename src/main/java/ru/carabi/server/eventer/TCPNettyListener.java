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

public class TCPNettyListener {
	private static final Logger logger = Logger.getLogger(TCPNettyListener.class.getName());
	private boolean released;
	private final DatagramSocket dsocket = null;



	public boolean isReleased() {
		return released;
	}

	public static void main(String[] args) {
		logger.log(Level.INFO, "starting listener");
		int port = 9234;
		logger.log(Level.INFO, "listening on port {0}", port);
		EventLoopGroup bossGroup = new NioEventLoopGroup();
		EventLoopGroup workerGroup = new NioEventLoopGroup();
		try {
			ServerBootstrap b = new ServerBootstrap();
			b.group(bossGroup, workerGroup)
			.channel(NioServerSocketChannel.class)
			.childHandler(new ChannelInitializer<SocketChannel>() {
				 @Override
				 public void initChannel(SocketChannel ch) throws Exception {
					logger.info("initChannel");
//					MessageEndpoint endpoint = endpointFactory.createEndpoint(null);
//					for (Class inter: endpoint.getClass().getInterfaces()) {
//						logger.info(inter.getName());
//					}
//					if (endpoint instanceof ru.carabi.server.messager.CarabiMessageListener) {
//						//((CarabiMessageListener)endpoint).onMessage(msg);
//						logger.info("This is ru.carabi.server.messager.CarabiMessageListener endpoint");
//					} else {
//						logger.severe("Not a ru.carabi.server.messager.CarabiMessageListener!");
//					}
					ch.pipeline().addLast(new NettyMessagesFilter(), new CarabiMessagerHandler());
				}
			})
			.option(ChannelOption.SO_BACKLOG, 128)
			.childOption(ChannelOption.SO_KEEPALIVE, true);

			// Bind and start to accept incoming connections.
			ChannelFuture f = b.bind(port).sync();

			// Wait until the server socket is closed.
			// In this example, this does not happen, but you can do that to gracefully
			// shut down your server.
			f.channel().closeFuture().sync();
		} catch (InterruptedException ex) {
			Logger.getLogger(TCPNettyListener.class.getName()).log(Level.SEVERE, null, ex);
		} finally {
			workerGroup.shutdownGracefully();
			bossGroup.shutdownGracefully();
		}
	}
}

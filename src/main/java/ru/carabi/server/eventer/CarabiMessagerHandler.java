package ru.carabi.server.eventer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.nio.charset.Charset;
import java.util.logging.Logger;
//import javax.resource.spi.endpoint.MessageEndpoint;

/**
 *
 * @author sasha
 */
public class CarabiMessagerHandler extends SimpleChannelInboundHandler<CarabiMessage> {
	private static final Logger logger = Logger.getLogger(CarabiMessagerHandler.class.getName());
//	private final MessageEndpoint endpoint;

	CarabiMessagerHandler() {
//		this.endpoint = endpoint;
//		logger.info("Get endpoint: " + endpoint.getClass().getName());
	}
	
	@Override
	protected void channelRead0(ChannelHandlerContext ctx, CarabiMessage msg) throws Exception {
		
		byte[] dataToPost;
		ByteBuf data = null;
		String answer;
		switch (msg.getType()) {
			case ping:
				answer = "PONG ПОНГ";
				dataToPost = answer.getBytes(Charset.forName("UTF-8"));
				data = ctx.alloc().buffer(dataToPost.length + 3);
				data.writeShort(CarabiMessage.Type.pong.getCode());
//				data.writeByte(CarabiMessage.Type.pong.getCode());
//				data.writeByte(0);
				break;
			case pong:
				return;
			case auth:
				String token = msg.getText();
				if (ClientSessionHolder.addSession(token, ctx)) {
					answer = "Клиент " + token + " авторизован!";
					dataToPost = answer.getBytes(Charset.forName("UTF-8"));
					data = ctx.alloc().buffer(dataToPost.length + 3);
					data.writeShort(CarabiMessage.Type.test.getCode());
//					data.writeByte(CarabiMessage.Type.test.getCode());
//					data.writeByte(0);
				} else {
					ctx.disconnect();
					return;
				}
				break;
			case synch: {
				return;
			}
			default:
				answer = "Клиент отправил сообщение: " + msg.getText() + " " + msg.getType().name();
				dataToPost = answer.getBytes(Charset.forName("UTF-8"));
				data = ctx.alloc().buffer(dataToPost.length + 3);
				data.writeShort(CarabiMessage.Type.test.getCode());
//				data.writeByte(CarabiMessage.Type.test.getCode());
//				data.writeByte(0);
		}
		logger.info(answer);
		data.writeBytes(dataToPost);
		data.writeByte(0);
		ctx.writeAndFlush(data);
	}
}

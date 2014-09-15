package ru.carabi.server.eventer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.Charset;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import static ru.carabi.server.eventer.CarabiMessage.Type.auth;

/**
 * Реализация бинарного протокола сообщений Carabi.
 * Клиент должен подключиться и отправить сообщение с токеном авторизации.
 * Сервер будет отправлять клиенту сообщения о системных событиях.
 * Каждое сообщение включает два байта с типом, строку с данными и терминальный ноль,
 * объём сообщения не должен превышать 10 КиБ
 */
public class MessagesHandler extends ChannelInboundHandlerAdapter {
	private static final Logger logger = Logger.getLogger(MessagesHandler.class.getName());
	private ChannelHandlerContext myctx;

	private final Properties utilProperties = new Properties();//Свойства привязанные к клиенту, не имеющие отношения к протоколу
	
	private boolean readHead = true; //в данный момент читаем заголовок (два байта)
	private short messageTypeCode;
	private String token;
	private ByteBuf readingBuffer = null;// = Unpooled.directBuffer();
	private ByteBuf messageBuffer;

	@Override
	public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
		super.channelRegistered(ctx); //To change body of generated methods, choose Tools | Templates.
		myctx = ctx;
		logger.info("channelRegistered");
		logger.setLevel(Level.FINE);
		readingBuffer = Unpooled.directBuffer();
	}
	
	/**
	 * Чтение данных из канала.
	 * Данные включаеют два байта (старший и младший) с типом сообщения, строку
	 * длиной до 10 КиБ в UTF8 и терминальный ноль.
	 */
	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (myctx != ctx) {
			logger.warning("New CTX!");
		}
		ByteBuf in = (ByteBuf) msg;
		//Копируем в локальный буфер
		readingBuffer.writeBytes(in);
		in.release();
		while (readingBuffer.isReadable()) {
			if (readHead) {//Читаем два байта заголовка
				if (readingBuffer.readableBytes() < 2) {
					return;
				}
				messageTypeCode = readingBuffer.readShort();
				readHead = false;
				messageBuffer = Unpooled.directBuffer(10240);
			}
			if (!readHead && readingBuffer.readableBytes() >= 1) {//Потом читаем до терминального нуля, складывая в отдельный буфер
				int bt = 0;
				while (readingBuffer.isReadable() && !readHead) {
					bt = (int) readingBuffer.readByte();
					if (bt == 0) {//Терминальный ноль
						readHead = true;
						String message = messageBuffer.toString(Charset.forName("UTF-8"));
						CarabiMessage.Type messageType= CarabiMessage.Type.getTypeByCode(messageTypeCode);
						if (messageType == null) {
							messageType = CarabiMessage.Type.error;
						}
						CarabiMessage carabiMessage = CarabiMessage.readCarabiMessage(message, messageType, this);
						if (carabiMessage.getType() == auth) {
							token = message;
						}
						ReferenceCountUtil.release(messageBuffer);
						carabiMessage.handle(token);
					} else {
						messageBuffer.writeByte(bt);
					}
				}
			}
		}
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		super.channelActive(ctx); //To change body of generated methods, choose Tools | Templates.
		logger.info("channelActive");
		if (myctx != ctx) {
			logger.warning("New CTX!");
		}
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		super.channelInactive(ctx); //To change body of generated methods, choose Tools | Templates.
		logger.info("channelInactive");
		if (myctx != ctx) {
			logger.warning("New CTX!");
		}
	}

	@Override
	public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
		super.channelUnregistered(ctx); //To change body of generated methods, choose Tools | Templates.
		logger.info("channelUnregistered");
		ClientsHolder.delClient(token);
		readingBuffer.clear();
		readingBuffer.release();
		if (utilProperties.getProperty("soapToken") != null) {
			try {
				SoapGateway.chatServicePort.fireUserState(token, false);
			} catch (Exception ex) {
				Logger.getLogger(MessagesHandler.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
		if (myctx != ctx) {
			logger.warning("New CTX!");
		}
	}
	
	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		// Close the connection when an exception is raised.
		logger.log(Level.SEVERE, "", cause);
		ctx.close();
	}

	ChannelHandlerContext getChannel() {
		return myctx;
	}
	public Properties getUtilProperties() {
		return utilProperties;
	}
}

package ru.carabi.server.eventer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.nio.charset.Charset;
import java.util.logging.Logger;

/**
 * Сообщение от клиента Carabi.
 * Полиморфный класс, обрабатывающий входящие сигналы и генерирующий ответы.
 * @author sasha
 */
public class CarabiMessage {
	private static final Logger logger = Logger.getLogger(CarabiMessage.class.getName());
	public enum Type {
		reserved(0),
		ping(1),
		pong(2),
		auth(3),
		test(4),
		
		synch(10);
		private short code;
		Type (int code) {
			if (code != (short)code) {
				throw new IllegalArgumentException("Too big code: " + code);
			}
			this.code = (short)code;
		}
		
		public short getCode () {
			return code;
		}
	}
	
	private final Type type;
	private final String text;
	private final ChannelHandlerContext ctx;
	
	/**
	 * Фабрика входящих сообщений.
	 * @param message Строка сообщения
	 * @param currentMessageType Код типа сообщенияи
	 * @param ctx Канал сессии
	 * @return формализованное сообщение для обработки.
	 */
	static CarabiMessage readCarabiMessage(String message, int messageTypeCode, ChannelHandlerContext ctx) {
		Type messageType= Type.values()[messageTypeCode];
		
		switch (messageType) {
			case ping:
				return new Ping(message, messageTypeCode, ctx);
			case pong:
				return new Pong(message, messageTypeCode, ctx);
			case auth:
				return new Auth(message, messageTypeCode, ctx);
//			case synch: {
//				return;
//			}
			default:
				return new CarabiMessage(message, messageTypeCode, ctx);
		}
	}
	public CarabiMessage(String src, int type, ChannelHandlerContext ctx) {
		this.text = src;
		this.type = Type.values()[type];
		this.ctx = ctx;
	}

	public ChannelHandlerContext getCtx() {
		return ctx;
	}
	
	public String getText() {
		return text;
	}
	
	public Type getType() {
		return type;
	}
	
	/**
	 * Обработка пришедшего сообщения.
	 * отправка ответа при необходимости.
	 */
	public void process() {
		short code = CarabiMessage.Type.test.getCode();
		String answer = "Клиент отправил сообщение: " + text + " " + type.name();
		sendAnswer(code, answer);
	}
	
	/**
	 * Отправка ответа.
	 * Отправка кода и текста сообщения с терминальным нулём в канал, переданный методу {@link readCarabiMessage}
	 * @param code код отправляемого ответа
	 * @param answer текст отправляемого ответа
	 */
	protected void sendAnswer(short code, String answer) {
		logger.info(answer);
		byte[] dataToPost = answer.getBytes(Charset.forName("UTF-8"));
		ByteBuf buffer = ctx.alloc().buffer(dataToPost.length + 3);
		buffer.writeShort(code);
//		data.writeByte(CarabiMessage.Type.test.getCode());
//		data.writeByte(0);
		buffer.writeBytes(dataToPost);
		buffer.writeByte(0);
		ctx.writeAndFlush(buffer);
	}
}
class Ping extends CarabiMessage {
	public Ping(String src, int type, ChannelHandlerContext ctx) {
		super(src, type, ctx);
	}
	@Override
	public void process() {
		short code = CarabiMessage.Type.pong.getCode();
		String answer = "PONG ПОНГ";
		sendAnswer(code, answer);
	}
}

class Pong extends CarabiMessage {
	public Pong(String src, int type, ChannelHandlerContext ctx) {
		super(src, type, ctx);
	}
	@Override
	public void process() {
		//не отвечаем
	}
}

class Auth extends CarabiMessage {
	public Auth(String src, int type, ChannelHandlerContext ctx) {
		super(src, type, ctx);
	}
	@Override
	public void process() {
		if (ClientSessionHolder.channelIsRegistered(getCtx())) {
			return;
		}
		String token = getText();
		if (ClientSessionHolder.addSession(token, getCtx())) {
		short code = CarabiMessage.Type.pong.getCode();
			String answer = "Клиент " + token + " авторизован!";
			sendAnswer(code, answer);
		} else {
			getCtx().disconnect();
		}
	}
}


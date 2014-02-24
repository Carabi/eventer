package ru.carabi.server.eventer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.nio.charset.Charset;
import java.util.logging.Level;
import java.util.logging.Logger;
import ru.carabi.server.soap.CarabiException_Exception;

/**
 * Сообщение от клиента Carabi.
 * Полиморфный класс, обрабатывающий входящие сигналы и генерирующий ответы.
 * @author sasha
 */
public class CarabiMessage {
	static final Logger logger = Logger.getLogger(CarabiMessage.class.getName());
	public enum Type {
		reserved(0),
		ping(1),
		pong(2),
		auth(3),
		test(4),
		
		synch(10),
		chatCount(11);
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
	final ChannelHandlerContext sessionContextChannel;
	
	/**
	 * Фабрика входящих сообщений.
	 * @param message Строка сообщения
	 * @param currentMessageType Код типа сообщенияи
	 * @param sessionContextChannel Канал сессии
	 * @return формализованное сообщение для обработки.
	 */
	static CarabiMessage readCarabiMessage(String message, int messageTypeCode, ChannelHandlerContext sessionContextChannel) {
		Type messageType= Type.values()[messageTypeCode];
		
		switch (messageType) {
			case ping:
				return new Ping(message, messageTypeCode, sessionContextChannel);
			case pong:
				return new Pong(message, messageTypeCode, sessionContextChannel);
			case auth:
				return new Auth(message, messageTypeCode, sessionContextChannel);
			case synch: {
				return new Sync(message, messageTypeCode, sessionContextChannel);
			}
			default:
				return new CarabiMessage(message, messageTypeCode, sessionContextChannel);
		}
	}
	public CarabiMessage(String src, int type, ChannelHandlerContext sessionContextChannel) {
		this.text = src;
		this.type = Type.values()[type];
		this.sessionContextChannel = sessionContextChannel;
	}

	public ChannelHandlerContext getCtx() {
		return sessionContextChannel;
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
	 * @param token токен подключившегося клиента
	 */
	public void process(String token) {
		short code = CarabiMessage.Type.test.getCode();
		String answer = "Клиент отправил сообщение: " + text + " " + type.name();
		sendAnswer(sessionContextChannel, code, answer);
	}
	
	/**
	 * Отправка ответа.
	 * Отправка кода и текста сообщения с терминальным нулём в канал, переданный методу {@link readCarabiMessage}
	 * @param sessionContextChannel
	 * @param code код отправляемого ответа
	 * @param answer текст отправляемого ответа
	 */
	protected static void sendAnswer(ChannelHandlerContext sessionContextChannel, short code, String answer) {
		logger.fine(answer);
		byte[] dataToPost = answer.getBytes(Charset.forName("UTF-8"));
		ByteBuf buffer = sessionContextChannel.alloc().buffer(dataToPost.length + 3);
		buffer.writeShort(code);
//		data.writeByte(CarabiMessage.Type.test.getCode());
//		data.writeByte(0);
		buffer.writeBytes(dataToPost);
		buffer.writeByte(0);
		sessionContextChannel.writeAndFlush(buffer);
	}
}
class Ping extends CarabiMessage {
	public Ping(String src, int type, ChannelHandlerContext sessionContextChannel) {
		super(src, type, sessionContextChannel);
	}
	@Override
	public void process(String token) {
		short code = CarabiMessage.Type.pong.getCode();
		String answer = "PONG ПОНГ";
		sendAnswer(sessionContextChannel, code, answer);
	}
}

class Pong extends CarabiMessage {
	public Pong(String src, int type, ChannelHandlerContext sessionContextChannel) {
		super(src, type, sessionContextChannel);
	}
	@Override
	public void process(String token) {
		//не отвечаем
	}
}

class Auth extends CarabiMessage {
	public Auth(String src, int type, ChannelHandlerContext sessionContextChannel) {
		super(src, type, sessionContextChannel);
	}
	@Override
	public void process(String token) {
		if (ClientSessionHolder.channelIsRegistered(getCtx())) {
			return;
		}
		if (ClientSessionHolder.addSession(token, getCtx())) {
		short code = CarabiMessage.Type.pong.getCode();
			String answer = "Клиент " + token + " авторизован!";
			sendAnswer(sessionContextChannel, code, answer);
		} else {
			getCtx().disconnect();
		}
	}
}

class Sync extends CarabiMessage {
	public Sync(String src, int type, ChannelHandlerContext sessionContextChannel) {
		super(src, type, sessionContextChannel);
	}
	@Override
	public void process(String token) {
		if (ClientSessionHolder.channelIsRegistered(getCtx())) {
			try {
				ClientSessionHolder.countUnreadMessages(sessionContextChannel, token);
			} catch (CarabiException_Exception ex) {
				Logger.getLogger(Sync.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
		
	}
}

package ru.carabi.server.eventer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonReader;
import javax.xml.ws.Holder;
import ru.carabi.server.soap.CarabiException_Exception;
import ru.carabi.server.soap.CarabiOracleException_Exception;
import ru.carabi.server.soap.QueryParameter;

/**
 * Сообщение от клиента Carabi.
 * Полиморфный класс, обрабатывающий входящие сигналы и генерирующий ответы.
 * @author sasha<kopilov.ad@gmail.com>
 */
public class CarabiMessage {
	static final Logger logger = Logger.getLogger(CarabiMessage.class.getName());
	public enum Type {
		reserved(0),
		ping(1),
		pong(2),
		auth(3),
		synch(4), //запросить события от сервера
		autosynch(5), //включить автоматическое получение событий
		disableSync(6),
		disableSyncAll(7),
		baseEventsTable(10),
		baseEventsList(11),
		error(Short.MAX_VALUE);
		
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
		
		private static Map<Short, Type> codeValueMap = new ConcurrentHashMap<>(10);
		
		static {
			for (Type type: Type.values()) {
				codeValueMap.put(type.code, type);
			}
		}
		public static Type getTypeByCode(short codeValue) {
			return codeValueMap.get(codeValue);
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
	static CarabiMessage readCarabiMessage(String message, short messageTypeCode, ChannelHandlerContext sessionContextChannel) {
		System.out.println(message);
		System.out.println(messageTypeCode);
		Type messageType= Type.getTypeByCode(messageTypeCode);
		System.out.println(messageType == null);
		
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
	
	/**
	 * Фабрика исходящих сообщений.
	 * @param currentMessageType Код типа сообщенияи
	 * @return формализованное сообщение для отправки.
	 */
	static CarabiMessage writeCarabiMessage(String inputMessage, short messageTypeCode, ChannelHandlerContext sessionContextChannel) {
		Type messageType= Type.getTypeByCode(messageTypeCode);
		switch (messageType) {
			case baseEventsTable:
				return new BaseEventsTable(inputMessage, messageTypeCode, sessionContextChannel);
			case baseEventsList:
				return new BaseEventsList(inputMessage, messageTypeCode, sessionContextChannel);
			default:
				return new CarabiMessage(inputMessage, messageTypeCode, sessionContextChannel);
		}
	}
	public CarabiMessage(String src, short type, ChannelHandlerContext sessionContextChannel) {
		this.text = src;
		this.type = Type.getTypeByCode(type);
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
		short code = CarabiMessage.Type.reserved.getCode();
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
		buffer.writeBytes(dataToPost);
		buffer.writeByte(0);
		sessionContextChannel.writeAndFlush(buffer);
	}
}
class Ping extends CarabiMessage {
	public Ping(String src, short type, ChannelHandlerContext sessionContextChannel) {
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
	public Pong(String src, short type, ChannelHandlerContext sessionContextChannel) {
		super(src, type, sessionContextChannel);
	}
	@Override
	public void process(String token) {
		//не отвечаем
	}
}

class Auth extends CarabiMessage {
	public Auth(String src, short type, ChannelHandlerContext sessionContextChannel) {
		super(src, type, sessionContextChannel);
	}
	@Override
	public void process(String token) {
		if (ClientSessionHolder.channelIsRegistered(getCtx())) {
			return;
		}
		if (ClientSessionHolder.addSession(token, getCtx())) {
		short code = CarabiMessage.Type.auth.getCode();
			String answer = "Клиент " + token + " авторизован!";
			sendAnswer(sessionContextChannel, code, answer);
		} else {
			getCtx().disconnect();
		}
	}
}

class Sync extends CarabiMessage {
	public Sync(String src, short type, ChannelHandlerContext sessionContextChannel) {
		super(src, type, sessionContextChannel);
	}
	@Override
	public void process(String token) {
		if (ClientSessionHolder.channelIsRegistered(getCtx())) {
			JsonReader eventsData = Json.createReader(new StringReader(getText()));
			JsonArray eventsToSend = eventsData.readArray();
			for (int i=0, n=eventsToSend.size(); i<n; i++) {
				CarabiMessage answer = writeCarabiMessage("", (short)eventsToSend.getInt(i), sessionContextChannel);
				answer.process(token);
			}
		}
	}
}

class BaseEventsTable extends CarabiMessage {
	public BaseEventsTable(String src, short type, ChannelHandlerContext sessionContextChannel) {
		super(src, type, sessionContextChannel);
	}
	@Override
	public void process(String token) {
		try {
			String answer;
			
			Holder<List<QueryParameter>> parameters = new Holder<>();
			parameters.value = new ArrayList<>();
			QueryParameter days = new QueryParameter();
			days.setName("DAYS");
			days.setValue("1");
			parameters.value.add(days);
			SoapGateway.queryServicePort.runStoredQuery(ClientSessionHolder.getSoapToken(token), "", "GET_NOTIFY_MESSAGES", -1, true, parameters);
			answer = parameters.value.get(0).getValue();
			CarabiMessage.sendAnswer(sessionContextChannel, CarabiMessage.Type.baseEventsTable.getCode(), answer);
		} catch (CarabiException_Exception | CarabiOracleException_Exception ex) {
			Logger.getLogger(BaseEventsTable.class.getName()).log(Level.SEVERE, null, ex);
			CarabiMessage.sendAnswer(sessionContextChannel, CarabiMessage.Type.error.getCode(), ex.getMessage());
		}
	}
	
}
class BaseEventsList extends CarabiMessage {

	public BaseEventsList(String src, short type, ChannelHandlerContext sessionContextChannel) {
		super(src, type, sessionContextChannel);
	}
	//answer = messageServicePort.getNotifyMessages(getSoapToken(token));
	@Override
	public void process(String token) {
		String answer;
		try {
			answer = SoapGateway.messageServicePort.getNotifyMessages(ClientSessionHolder.getSoapToken(token));
			CarabiMessage.sendAnswer(sessionContextChannel, CarabiMessage.Type.baseEventsTable.getCode(), answer);
		} catch (CarabiException_Exception | CarabiOracleException_Exception ex) {
			Logger.getLogger(BaseEventsList.class.getName()).log(Level.SEVERE, null, ex);
			CarabiMessage.sendAnswer(sessionContextChannel, CarabiMessage.Type.error.getCode(), ex.getMessage());
		}
	}
}


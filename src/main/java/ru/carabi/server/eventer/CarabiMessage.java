package ru.carabi.server.eventer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonReader;
import javax.xml.ws.Holder;
import ru.carabi.libs.CarabiFunc;
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
		/**
		 * При получании от клиента -- условный ответ, передача через fireEvent без изменений.
		 * Содержимое пакета произвольное.
		 */
		reserved(0),
		/**
		 * При получении от клиента ответ pong, на сервере генерируется по таймеру
		 * с ожиданием так же ответа pong для контроля подключения и продления 
		 * Glassfish-сессии. Содержимое пакета может быть произвольным (традиционное: "PING ПИНГ")
		 */
		ping(1),
		/**
		 * Ответ не генерируется, при получении сигнала раз в 5 минут продливается сессия на Glassfish.
		 * Содержимое пакета может быть произвольным (традиционное: "PONG ПОНГ")
		 */
		pong(2),
		/**
		 * Авторизация в Eventer с помощью зашифрованного токена от Glassfish.
		 * Содержимое пакета: зашифрованный токен, получаемый через EventerService.getEventerToken
		 */
		auth(3),
		/**
		 * Запросить события с сервера. В ответ приходят сообщения запрошенных типов.
		 * Содержимое пакета: JSON-массив типлв пакетов (например, '[10,11]')
		 */
		synch(4),
		/**
		 * Оключить автоматическое получение событий. Сообщения запрошенных
		 * типов будут приходить по таймеру по мере их появления в системе.
		 * Содержимое пакета: JSON-массив типлв пакетов (например, '[10,11]')
		 */
		autosynch(5),
		/**
		 * Отключение autosynch(5) для определёного типа событий.
		 * Содержимое пакета: JSON-массив типлв пакетов (например, '[10,11]')
		 */
		disableSync(6),
		/**
		 * Отключение autosynch(5) для всех типов событий. Содержимое пакета: пустой.
		 */
		disableSyncAll(7),
		/**
		 * Разослать событие другим клиентам, подключенным к серверу.
		 * Содержимое пакета: JSON-объект с названием базы-источника (при наличии),
		 * логина адресата (если нет, но указана база -- сообщение получают все, кто
		 * работает с этой базой),типа и содержимого пакета.
		 * Пример: {"schema":"carabi", "login":"user", "eventcode":0, "message":"test"}
		 * Все сообщения пересылаются клиентам без изменений.
		 */
		fireEvent(8),
		shutdown(9),
		/**
		 * статистические события в общем массиве (формат: JSON с выделенной шапкой)
		 */
		baseEventsTable(10),
		/**
		 * статистические события в общем массиве (формат: JSON, ассоциативный массив)
		 */
		baseEventsList(11),
		chatMessage(12),
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
		
		private final static Map<Short, Type> codeValueMap = new ConcurrentHashMap<>(16);
		
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
	private final ChannelHandlerContext ctx;
	private final MessagesHandler client;
	
	/**
	 * Фабрика входящих сообщений.
	 * @param message Строка сообщения
	 * @param currentMessageType Код типа сообщенияи
	 * @param ctx Канал сессии
	 * @return формализованное сообщение для обработки.
	 */
	static CarabiMessage readCarabiMessage(String message, Type messageType, MessagesHandler client) {
		switch (messageType) {
			case ping:
				return new Ping(message, messageType, client);
			case pong:
				return new Pong(message, messageType, client);
			case auth:
				return new Auth(message, messageType, client);
			case synch: 
				return new Sync(message, messageType, client);
			case autosynch:
				return new Autosynch(message, messageType, client);
			case disableSync:
				return new DisableSync(message, messageType, client);
			case disableSyncAll:
				return new DisableSyncAll(message, messageType, client);
			case fireEvent:
				return new FireEvent(message, messageType, client);
			case shutdown:
				return new Shutdown(message, messageType, client);
			default:
				return new CarabiMessage(message, messageType, client);
		}
	}
	
	/**
	 * Фабрика исходящих сообщений.
	 * @param prevMessage Предыдущее сообщение или на которое отвечаем (если не генерируем событие сами)
	 * @param currentMessageType Код типа сообщения
	 * @param force обязательно отправлять сообщение, даже если ошибка или нет новых событий
	 * @return формализованное сообщение для отправки.
	 */
	static CarabiMessage writeCarabiMessage(String prevMessage, Type messageType, boolean force, MessagesHandler client) {
		switch (messageType) {
			case ping:
				return new Ping(null, messageType, client);
			case baseEventsTable:
				return new BaseEventsTable(prevMessage, messageType, force, client);
			case baseEventsList:
				return new BaseEventsList(prevMessage, messageType, force, client);
			case chatMessage:
				return new ChatMessage(prevMessage, messageType, force, client);
			default:
				return new CarabiMessage(prevMessage, messageType, client);
		}
	}
	public CarabiMessage(String text, Type type, MessagesHandler client) {
		this.text = text;
		this.type = type;
		this.ctx = client.getChannel();
		this.client = client;
	}

	public ChannelHandlerContext getCtx() {
		return ctx;
	}
	
	
	public MessagesHandler getClient() {
		return client;
	}

	public String getText() {
		return text;
	}
	
	public Type getType() {
		return type;
	}
	
	/**
	 * Обработка пришедшего сообщения (возможно, с созданием ответа).
	 * @param token токен подключившегося клиента
	 */
	public void handle(String token) {
		String answer = "Got the message: " + text + " " + type.name();
		sendMessage(ctx, Type.reserved, answer);
	}
	
	/**
	 * Создание сообщения по инициативе сервера.
	 * @param token токен подключившегося клиента
	 */
	public void post(String token) {
		sendMessage(ctx, CarabiMessage.Type.reserved, text);
	}
	
	/**
	 * Отправка сообщения.
	 * Отправка кода и текста сообщения с терминальным нулём
	 * @param sessionContextChannel канал
	 * @param type тип отправляемого сообщения
	 * @param messageText текст отправляемого сообщения
	 */
	protected static final void sendMessage(ChannelHandlerContext sessionContextChannel, Type type, String messageText) {
		short code = type.getCode();
		sendMessage(sessionContextChannel, code, messageText);
	}
	/**
	 * Отправка сообщения.
	 * Отправка кода и текста сообщения с терминальным нулём
	 * @param sessionContextChannel канал
	 * @param code код типа отправляемого сообщения
	 * @param messageText текст отправляемого сообщения
	 */
	protected static final void sendMessage(ChannelHandlerContext sessionContextChannel, short code, String messageText) {
		logger.fine(messageText);
		byte[] dataToPost = messageText.getBytes(Charset.forName("UTF-8"));
		ByteBuf buffer = sessionContextChannel.alloc().buffer(dataToPost.length + 3);
		buffer.writeShort(code);
		buffer.writeBytes(dataToPost);
		buffer.writeByte(0);
		sessionContextChannel.writeAndFlush(buffer);
	}
	
	protected Collection<CarabiMessage.Type> parseMessageTypes(String messageTypesJson) {
		JsonReader eventsData = Json.createReader(new StringReader(messageTypesJson));
		JsonArray eventsToSend = eventsData.readArray();
		Collection<Type> types = new ArrayList<>();
		for (int i=0, n=eventsToSend.size(); i<n; i++) {
			types.add(Type.getTypeByCode((short)eventsToSend.getInt(i)));
		}
		return types;
	}
}

class Shutdown extends CarabiMessage {
	public Shutdown(String text, Type type, MessagesHandler client) {
		super(text, type, client);
	}
	@Override
	public void handle(String token) {
		//отключение должно производиться в два этапа: получение ключа и отправка пакета с зашифрованным ключом.
		String encryptedKey = getText();
		if (encryptedKey == null || encryptedKey.equals("")) {
			String key = CarabiFunc.getRandomString(128);
			getClient().getUtilProperties().setProperty("shutdownKey", key);
			sendMessage(getCtx(), CarabiMessage.Type.shutdown, key);
		} else {
			try {
				String key = CarabiFunc.decrypt(encryptedKey);
				if (key.equals(getClient().getUtilProperties().getProperty("shutdownKey"))) {
					sendMessage(getCtx(), CarabiMessage.Type.shutdown, "shutdownOK");
					Main.shutdown();
				}
			} catch (GeneralSecurityException e) {
				
			}
		}
	}
}

class Ping extends CarabiMessage {
	public Ping(String src, Type type, MessagesHandler client) {
		super("PING ПИНГ", type, client);
	}
	@Override
	public void handle(String token) {
		String answer = "PONG ПОНГ";
		sendMessage(getCtx(), Type.pong, answer);
	}
	
	@Override
	public void post(String token){
		sendMessage(getCtx(), Type.ping, getText());
	}
}

class Pong extends CarabiMessage {
	public Pong(String src, Type type, MessagesHandler client) {
		super(src, type, client);
	}
	@Override
	public void handle(String token) {
		final Properties utilProlerties = getClient().getUtilProperties();
		//не отвечаем, если шла проверка сессии -- передаём на Glassfish
		if ("true".equals(utilProlerties.getProperty("testingSession"))) {
			utilProlerties.setProperty("testingSession", "false");
			try {
				SoapGateway.guestServicePort.getUserInfo(getClient().getUtilProperties().getProperty("soapToken"));
			} catch (CarabiException_Exception ex) {
				Logger.getLogger(Pong.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
	}
}

class Auth extends CarabiMessage {
	public Auth(String src, Type type, MessagesHandler client) {
		super(src, type, client);
	}
	@Override
	public void handle(String token) {
		if (ClientsHolder.clientlIsRegistered(token)) {
			return;
		}
		if (ClientsHolder.addClient(token, getClient())) {
			String answer = "Клиент " + token + " авторизован!";
			sendMessage(getCtx(), Type.auth, answer);
		} else {
			getCtx().disconnect();
		}
	}
}

class Sync extends CarabiMessage {
	public Sync(String src, Type type, MessagesHandler client) {
		super(src, type, client);
	}
	@Override
	public void handle(String token) {
		if (ClientsHolder.clientlIsRegistered(token)) {
			Collection<Type> eventsToSend = parseMessageTypes(getText());
			for (Type type: eventsToSend) {
				CarabiMessage answer = writeCarabiMessage("", type, true, getClient());
				answer.post(token);
			}
		}
	}
}

class Autosynch extends CarabiMessage {
	public Autosynch(String src, Type type, MessagesHandler client) {
		super(src, type, client);
	}
	@Override
	public void handle(String token) {
		if (ClientsHolder.clientlIsRegistered(token)) {
			Collection<CarabiMessage.Type> types = parseMessageTypes(getText());
			ClientsHolder.addEventTypes(token, types);
		}
	}
}

class DisableSync extends CarabiMessage {
	public DisableSync(String src, Type type, MessagesHandler client) {
		super(src, type, client);
	}
	@Override
	public void handle(String token) {
		if (ClientsHolder.clientlIsRegistered(token)) {
			Collection<CarabiMessage.Type> types = parseMessageTypes(getText());
			ClientsHolder.removeEventTypes(token, types);
		}
	}
}

class DisableSyncAll extends CarabiMessage {
	public DisableSyncAll(String src, Type type, MessagesHandler client) {
		super(src, type, client);
	}
	@Override
	public void handle(String token) {
		if (ClientsHolder.clientlIsRegistered(token)) {
			ClientsHolder.clearEventTypes(token);
		}
	}
}

class FireEvent extends CarabiMessage {
	public FireEvent(String src, Type type, MessagesHandler client) {
		super(src, type, client);
	}
	@Override
	public void handle(String token) {
		try {
			ClientsHolder.fireEvent(getText());
		} catch (Exception ex) {
			Logger.getLogger(FireEvent.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
}

class BaseEventsTable extends CarabiMessage {
	boolean force;
	public BaseEventsTable(String src, Type type, boolean force, MessagesHandler client) {
		super(src, type, client);
		this.force = force;
	}
	@Override
	public void post(String token) {
		try {
			String answer;
			Holder<List<QueryParameter>> parameters = new Holder<>();
			parameters.value = new ArrayList<>();
			QueryParameter days = new QueryParameter();
			days.setName("DAYS");
			days.setValue("1");
			parameters.value.add(days);
			SoapGateway.queryServicePort.runStoredQuery(ClientsHolder.getSoapToken(token), "", "GET_NOTIFY_MESSAGES", -1, true, parameters);
			answer = parameters.value.get(0).getValue();
			if (force || !answer.equals(getText())) {
				sendMessage(getCtx(), getType(), answer);
			}
			ClientsHolder.setLastEvent(token, getType(), answer);
		} catch (CarabiException_Exception | CarabiOracleException_Exception ex) {
			Logger.getLogger(BaseEventsTable.class.getName()).log(Level.SEVERE, null, ex);
			if (force) {
				sendMessage(getCtx(), Type.error, ex.getMessage());
			}
		}
	}
	
}
class BaseEventsList extends CarabiMessage {
	boolean force;
	public BaseEventsList(String src, Type type, boolean force, MessagesHandler client) {
		super(src, type, client);
		this.force = force;
	}
	//answer = messageServicePort.getNotifyMessages(getSoapToken(token));
	@Override
	public void post(String token) {
		String answer;
		try {
			answer = SoapGateway.messageServicePort.getNotifyMessages(ClientsHolder.getSoapToken(token));
			if (force || !answer.equals(getText())) {
				sendMessage(getCtx(), getType(), answer);
			}
			ClientsHolder.setLastEvent(token, getType(), answer);
		} catch (CarabiException_Exception | CarabiOracleException_Exception ex) {
			Logger.getLogger(BaseEventsList.class.getName()).log(Level.SEVERE, null, ex);
			sendMessage(getCtx(), Type.error, ex.getMessage());
		}
	}
}
class ChatMessage extends CarabiMessage {
	boolean force;
	public ChatMessage(String src, CarabiMessage.Type type, boolean force, MessagesHandler client) {
		super(src, type, client);
		this.force = force;
	}
}


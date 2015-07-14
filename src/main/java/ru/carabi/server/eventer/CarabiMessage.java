package ru.carabi.server.eventer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.xml.ws.Holder;
import ru.carabi.libs.CarabiFunc;
import ru.carabi.libs.CarabiEventType;
import ru.carabi.stub.CarabiException_Exception;
import ru.carabi.stub.CarabiOracleException_Exception;
import ru.carabi.stub.QueryParameter;

/**
 * Сообщение от клиента Carabi.
 * Полиморфный класс, обрабатывающий входящие сигналы и генерирующий ответы.
 * @author sasha<kopilov.ad@gmail.com>
 */
public class CarabiMessage {
	static final Logger logger = Logger.getLogger(CarabiMessage.class.getName());
	
	private final CarabiEventType type;
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
	static CarabiMessage readCarabiMessage(String message, CarabiEventType messageType, MessagesHandler client) {
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
			case userOnlineQuery:
				return new UserOnlineQuery(message, messageType, client);
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
	static CarabiMessage writeCarabiMessage(String prevMessage, CarabiEventType messageType, boolean force, MessagesHandler client) {
		switch (messageType) {
			case ping:
				return new Ping(null, messageType, client);
			case baseEventsTable:
				return new BaseEventsTable(prevMessage, messageType, force, client);
			case baseEventsList:
				return new BaseEventsList(prevMessage, messageType, force, client);
			default:
				return new CarabiMessage(prevMessage, messageType, client);
		}
	}
	public CarabiMessage(String text, CarabiEventType type, MessagesHandler client) {
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
	
	public CarabiEventType getType() {
		return type;
	}
	
	/**
	 * Обработка пришедшего сообщения (возможно, с созданием ответа).
	 * @param token токен подключившегося клиента
	 */
	public void handle(String token) {
		String answer = "Got the message: " + text + " " + type.name();
		sendMessage(ctx, CarabiEventType.reserved, answer);
	}
	
	/**
	 * Создание сообщения по инициативе сервера.
	 * @param token токен подключившегося клиента
	 */
	public void post(String token) {
		sendMessage(ctx, type, text);
	}
	
	/**
	 * Отправка сообщения.
	 * Отправка кода и текста сообщения с терминальным нулём
	 * @param sessionContextChannel канал
	 * @param type тип отправляемого сообщения
	 * @param messageText текст отправляемого сообщения
	 */
	protected static final void sendMessage(ChannelHandlerContext sessionContextChannel, CarabiEventType type, String messageText) {
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
	
	protected Collection<CarabiEventType> parseMessageTypes(String messageTypesJson) {
		JsonReader eventsData = Json.createReader(new StringReader(messageTypesJson));
		JsonArray eventsToSend = eventsData.readArray();
		Collection<CarabiEventType> types = new ArrayList<>();
		for (int i=0, n=eventsToSend.size(); i<n; i++) {
			types.add(CarabiEventType.getTypeByCode((short)eventsToSend.getInt(i)));
		}
		return types;
	}
}

class Shutdown extends CarabiMessage {
	public Shutdown(String text, CarabiEventType type, MessagesHandler client) {
		super(text, type, client);
	}
	@Override
	public void handle(String token) {
		//отключение должно производиться в два этапа: получение ключа и отправка пакета с зашифрованным ключом.
		String encryptedKey = getText();
		if (encryptedKey == null || encryptedKey.equals("")) {
			String key = CarabiFunc.getRandomString(128);
			getClient().getUtilProperties().setProperty("shutdownKey", key);
			sendMessage(getCtx(), CarabiEventType.shutdown, key);
		} else {
			try {
				String key = CarabiFunc.decrypt(encryptedKey);
				if (key.equals(getClient().getUtilProperties().getProperty("shutdownKey"))) {
					sendMessage(getCtx(), CarabiEventType.shutdown, "shutdownOK");
					Main.shutdown();
				}
			} catch (GeneralSecurityException e) {
				
			}
		}
	}
}

class Ping extends CarabiMessage {
	public Ping(String src, CarabiEventType type, MessagesHandler client) {
		super("PING ПИНГ", type, client);
	}
	@Override
	public void handle(String token) {
		String answer = "PONG ПОНГ";
		sendMessage(getCtx(), CarabiEventType.pong, answer);
	}
	
	@Override
	public void post(String token){
		sendMessage(getCtx(), CarabiEventType.ping, getText());
	}
}

class Pong extends CarabiMessage {
	public Pong(String src, CarabiEventType type, MessagesHandler client) {
		super(src, type, client);
	}
	@Override
	public void handle(String token) {
		final Properties utilProlerties = getClient().getUtilProperties();
		//не отвечаем, если шла проверка сессии -- передаём на Glassfish
		if ("true".equals(utilProlerties.getProperty("testingSession"))) {
			utilProlerties.setProperty("testingSession", "false");
			new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						SoapGateway.guestServicePort.getUserInfo(getClient().getUtilProperties().getProperty("soapToken"));
					} catch (CarabiException_Exception ex) {
						Logger.getLogger(Pong.class.getName()).log(Level.SEVERE, null, ex);
					}
				}
			}).start();
		}
	}
}

class Auth extends CarabiMessage {
	public Auth(String src, CarabiEventType type, MessagesHandler client) {
		super(src, type, client);
	}
	@Override
	public void handle(final String token) {
		if (ClientsHolder.clientlIsRegistered(token)) {
			return;
		}
		if (ClientsHolder.addClient(token, getClient())) {
			String answer = "Клиент " + token + " авторизован!";
			logger.fine(answer);
			sendMessage(getCtx(), CarabiEventType.auth, answer);
			getCtx().flush();
			new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						SoapGateway.chatServicePort.fireUserState(token, true);
					} catch (Exception ex) {
						Logger.getLogger(Auth.class.getName()).log(Level.SEVERE, null, ex);
					}
				}
			}).start();
		} else {
			getCtx().disconnect();
		}
	}
}

class Sync extends CarabiMessage {
	public Sync(String src, CarabiEventType type, MessagesHandler client) {
		super(src, type, client);
	}
	@Override
	public void handle(String token) {
		if (ClientsHolder.clientlIsRegistered(token)) {
			Collection<CarabiEventType> eventsToSend = parseMessageTypes(getText());
			for (CarabiEventType type: eventsToSend) {
				CarabiMessage answer = writeCarabiMessage("", type, true, getClient());
				answer.post(token);
			}
		}
	}
}

class Autosynch extends CarabiMessage {
	public Autosynch(String src, CarabiEventType type, MessagesHandler client) {
		super(src, type, client);
	}
	@Override
	public void handle(String token) {
		if (ClientsHolder.clientlIsRegistered(token)) {
			Collection<CarabiEventType> types = parseMessageTypes(getText());
			ClientsHolder.addEventTypes(token, types);
		}
	}
}

class DisableSync extends CarabiMessage {
	public DisableSync(String src, CarabiEventType type, MessagesHandler client) {
		super(src, type, client);
	}
	@Override
	public void handle(String token) {
		if (ClientsHolder.clientlIsRegistered(token)) {
			Collection<CarabiEventType> types = parseMessageTypes(getText());
			ClientsHolder.removeEventTypes(token, types);
		}
	}
}

class DisableSyncAll extends CarabiMessage {
	public DisableSyncAll(String src, CarabiEventType type, MessagesHandler client) {
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
	public FireEvent(String src, CarabiEventType type, MessagesHandler client) {
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
	public BaseEventsTable(String src, CarabiEventType type, boolean force, MessagesHandler client) {
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
				sendMessage(getCtx(), CarabiEventType.error, ex.getMessage());
			}
		}
	}
	
}
class BaseEventsList extends CarabiMessage {
	boolean force;
	public BaseEventsList(String src, CarabiEventType type, boolean force, MessagesHandler client) {
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
			sendMessage(getCtx(), CarabiEventType.error, ex.getMessage());
		}
	}
}
class ChatMessage extends CarabiMessage {
	boolean force;
	public ChatMessage(String src, CarabiEventType type, MessagesHandler client) {
		super(src, type, client);
	}
}
class UserOnlineQuery extends CarabiMessage {
	public UserOnlineQuery(String text, CarabiEventType type, MessagesHandler client) {
		super(text, type, client);
	}
	@Override
	public void handle(String token) {
		String usersJson = getText();
		JsonArray usersQueried = Json.createReader(new StringReader(usersJson)).readArray();
		Set<String> usersOnline = ClientsHolder.getUsersOnline();
		JsonObjectBuilder result = Json.createObjectBuilder();
		final int size = usersQueried.size();
		if (size > 0) {
			for (int i=0; i<size; i++) {
				String login = usersQueried.getString(i);
				result.add(login, usersOnline.contains(login));
			}
		} else {
			for (String login: usersOnline) {
				result.add(login, true);
			}
		}
		String answer = result.build().toString();
		sendMessage(getCtx(), getType(), answer);
	}
}

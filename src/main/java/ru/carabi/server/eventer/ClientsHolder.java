package ru.carabi.server.eventer;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.internal.ConcurrentSet;
import java.io.StringReader;
import java.security.GeneralSecurityException;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.json.Json;
import javax.json.JsonObject;
import ru.carabi.libs.CarabiEventType;
import ru.carabi.libs.CarabiFunc;
import ru.carabi.stub.CarabiException_Exception;

/**
 * Контейнер клиентских сессий.
 * При подключении клиента к NettyListener соединение сохраняется тут для дальнейшей
 * передачи пакетов по инициативе сервера.
 * 
 * @author sasha<kopilov.ad@gmail.com>
 */
public class ClientsHolder {
	private static final Logger logger = Logger.getLogger(ClientsHolder.class.getName());
	
	static {
		createOnlineMonitorTimer();
	}
	/**
	 * Пользовательские сессии (по токенам)
	 */
	private static final ConcurrentHashMap<String, SessionTimer> sessions = new ConcurrentHashMap<>();
	
	/**
	 * Когда пользователь (по логину) выполнял последнее действие
	 */
	private static final ConcurrentHashMap<String, Date> usersLastactive = new ConcurrentHashMap<>();
	
	/**
	 * Подключен ли пользователь сейчас
	 */
	private static final ConcurrentHashMap<String, Boolean> usersOnline = new ConcurrentHashMap<>();
	
	/**
	 * Недавно отключившиеся пользователи (если снова не подключатся -- рассылаем событие)
	 */
	private static final ConcurrentHashMap<String, String> disconnectedRecently = new ConcurrentHashMap<>();
	
	/**
	 * Сессии и пользователи
	 */
	private static final ConcurrentHashMap<String, String> loginPerToken = new ConcurrentHashMap<>();
	
	/**
	 * Клиент подключился или отключился. Если клиент подключился и не был на
	 * сервере больше минуты -- отправить событие. Если клиент отключился --
	 * подождать минуту (он может подключиться снова, если порвалась связь),
	 * если не подключился -- отправить событие.
	 * @param token eventer-токен (зашифрованный soap-токен) сессии
	 * @param login логин клиента
	 * @param isOnline подключился или отключился
	 */
	public static void setUserOnline(final String token, String login, boolean isOnline) {
		logger.log(Level.FINE, "setUserOnline({0}, {1}, {2})", new Object[]{token, login, isOnline});
		Date lastactive = new Date(0);
		Date currentTime = new Date();
		if (usersLastactive.containsKey(login)) {
			lastactive = usersLastactive.get(login);
		}
		boolean wasOnline = false;
		if (usersOnline.containsKey(login)) {
			wasOnline = usersOnline.get(login);
		}
		usersLastactive.put(login, currentTime);
		usersOnline.put(login, isOnline);
		if (wasOnline == isOnline) {
			return;
		}
		if (wasOnline == false && isOnline == true) {
			disconnectedRecently.remove(login);
			if ((currentTime.getTime() - lastactive.getTime()) > CarabiFunc.onlineControlTimeout * 1000) {
				logger.log(Level.FINE, "fireUserStateSoap({0}, true)", token);
				fireUserStateSoap(token, true);
			}
		}
		if (wasOnline == true && isOnline == false) {
			disconnectedRecently.put(login, token);
		}
	}

	private static void fireUserStateSoap(final String token, final boolean online) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					SoapGateway.chatServicePort.fireUserState(token, online);
				} catch (Exception ex) {
					Logger.getLogger(Auth.class.getName()).log(Level.SEVERE, null, ex);
				}
			}
		}).start();
	}
	
	public static void setSessionOnline(String token, Boolean isOnline) {
		setUserOnline(token, loginPerToken.get(token), isOnline);
	}
	
	public static void updateLastActive(String token) {
		String login = loginPerToken.get(token);
		if (login != null) {
			usersLastactive.put(login, new Date());
		}
	}
	
	private static Thread onlineMonitorTimer;
	
	private static void createOnlineMonitorTimer() {
		onlineMonitorTimer = new Thread(new Runnable() {
			@Override
			public synchronized void run() {
				//каждые 10 секунд смотрим, есть ли недавно отключившиеся
				//пользователи. При отключении более минуты назад шлём событие
				long currentTime;
				while (true) {
					try {
						currentTime = new Date().getTime();
						for (Map.Entry<String, String> disconnectedUserSession : disconnectedRecently.entrySet()) {
							String login = disconnectedUserSession.getKey();
							String token = disconnectedUserSession.getValue();
							long lastactive = usersLastactive.get(login).getTime();
							if ((currentTime - lastactive) > CarabiFunc.onlineControlTimeout * 1000) {
								fireUserStateSoap(token, false);
							}
						}
						wait(1000 * 10);
					} catch (InterruptedException ex) {
						Logger.getLogger(ClientsHolder.class.getName()).log(Level.SEVERE, null, ex);
					}
				}
			}
		});
		onlineMonitorTimer.start();
	}
	
	/**
	 * Добавить подключение.
	 * Если пользователь авторизован (токен есть в системе) &mdash; сохраннить сессию,
	 * если нет &mdash; отклонить.
	 * @param eventerToken токен, полученный функцией getEventerToken SOAP-сервера.
	 * @param client сохраняемая сессия.
	 * @return была ли сохранена сессия.
	 */
	public static boolean addClient(String eventerToken, MessagesHandler client) {
		try {
			String soapToken = CarabiFunc.decrypt(eventerToken);
			client.getUtilProperties().setProperty("soapToken", soapToken);
			SessionTimer sessionTimer = new SessionTimer(eventerToken, soapToken, client);
			sessions.put(eventerToken, sessionTimer);
			new Thread(sessionTimer).start();
			return true;
		} catch (GeneralSecurityException | CarabiException_Exception ex) {
			logger.log(Level.INFO, null, ex);
			return false;
		}
	}
	
	public static boolean clientlIsRegistered(String token) {
		return sessions.containsKey(token);
	}
	
	public static void delClient(String token) {
		if (token == null) {
			return;
		}
		SessionTimer sessionTimer = sessions.remove(token);
		if (sessionTimer == null) {
			return;
		}
		sessionTimer.active = false;
	}
	
	/**
	 * Получить логины пользователей, подключённых к Eventer в данный момент.
	 * @return 
	 */
	static Set<String> getUsersOnline() {
		Set<String> users = new HashSet<>();
		for (Map.Entry<String, Boolean> userOnline: usersOnline.entrySet()) {
			if (userOnline.getValue()) {
				users.add(userOnline.getKey());
			}
		}
		return users;
	}

	/**
	 * Сессия пользователя с таймером для отправки статистических событий.
	 * По таймеру происходит опрос БД и отправка сообщений клиенту.
	 */
	private static class SessionTimer implements Runnable {
		private static final int EVENT_INTERVAL = 5;//интервал таймера проверки событий в секундах
		private static final int SESSION_INTERVAL = 30;//интервал обновления сессии
		boolean active = true;
		String schema;
		String login;
		int userId;
		String soapToken;
		String eventerToken;
		MessagesHandler client;
		ChannelHandlerContext sessionContextChannel;
		Set<CarabiEventType> whatToSend = new ConcurrentSet<>();//типы событий, которые должны приходить клиенту автоматически
		Map<CarabiEventType, String> oldEvents = new ConcurrentHashMap<>();//события по типам, приходившие клиенту ранее
		
		SessionTimer(String eventerToken, String soapToken, MessagesHandler client) throws CarabiException_Exception {
			active = true;
			this.eventerToken = eventerToken;
			this.soapToken = soapToken;
			this.client = client;
			this.sessionContextChannel = client.getChannel();
			String userInfoJson = SoapGateway.guestServicePort.getUserInfo(soapToken);
			logger.fine(userInfoJson);
			JsonObject userInfo = Json.createReader(new StringReader(userInfoJson)).readObject();
			schema = userInfo.getString("schema", "");
			login = userInfo.getString("login");
			userId = userInfo.getInt("carabiUserID");
			loginPerToken.put(eventerToken, login);
		}
		
		@Override
		public synchronized void run() {
			int sessionCounter = 0;//
			while (active && !sessionContextChannel.isRemoved()) {
				//Проверяем наличие требуемых (whatToSend) событий
				for (CarabiEventType type: whatToSend) {
					CarabiMessage event = CarabiMessage.writeCarabiMessage(oldEvents.get(type), type, false, client);
					event.post(eventerToken);
				}
				//пауза EVENT_INTERVAL
				try {
					wait(EVENT_INTERVAL * 1000);
				} catch (InterruptedException ex) {
					active = false;
					logger.log(Level.SEVERE, null, ex);
				}
				sessionCounter += EVENT_INTERVAL;
				if (sessionCounter >= SESSION_INTERVAL) {
					//Каждые SESSION_INTERVAL ставим testingSession true для уведомления сервера с базой
					client.getUtilProperties().setProperty("testingSession", "true");
					sessionCounter = 0;
					//обмениваемся пингами с клиентом
					CarabiMessage event = CarabiMessage.writeCarabiMessage("TEST_SESSION_PING", CarabiEventType.ping, false, client);
					event.post(eventerToken);
				}
			}
		}
	}
	
	/**
	 * Получение SOAP-токена по входному токену Eventer-а для авторизовавшихся пользователей.
	 * Результат должен совпадать с результатом функции decrypt, но предполагается
	 * большая скорость работы (не расшифровка, а чтение из памяти).
	 * @return 
	 */
	public static String getSoapToken(String eventerToken) {
		return sessions.get(eventerToken).soapToken;
	}
	
	/**
	 * Включить события у пользователя.
	 * Пользователь с указанным токеном начнёт автоматически получать оповещения о
	 * событиях указанного типа
	 * @param eventerToken токен пользователя
	 * @param eventTypes типы событий, о которых оповещать
	 */
	public static void addEventTypes(String eventerToken, Collection<CarabiEventType> eventTypes) {
		sessions.get(eventerToken).whatToSend.addAll(eventTypes);
	}
	/**
	 * Отключить события у пользователя.
	 * @param eventerToken токен пользователя
	 * @param eventTypes типы событий, о которых не оповещать
	 */
	public static void removeEventTypes(String eventerToken, Collection<CarabiEventType> eventTypes) {
		sessions.get(eventerToken).whatToSend.removeAll(eventTypes);
	}
	
	static void clearEventTypes(String eventerToken) {
		sessions.get(eventerToken).whatToSend.clear();
	}
	
	public static void setLastEvent(String eventerToken, CarabiEventType eventType, String eventText) {
		sessions.get(eventerToken).oldEvents.put(eventType, eventText);
	}
	
	public static void fireEvent(String encryptedEventPackage) throws Exception {
		String eventPackageJson = CarabiFunc.decrypt(encryptedEventPackage);
		logger.log(Level.FINE, "fireEvent: {0}", eventPackageJson);
		JsonObject eventPackage = Json.createReader(new StringReader(eventPackageJson)).readObject();
		String schema = eventPackage.getString("schema");
		String login = eventPackage.getString("login");
		int eventCode = eventPackage.getInt("eventcode");
		String message = eventPackage.getString("message");
		for (SessionTimer session: sessions.values()) {
			logger.log(Level.FINE, "messsage to {0}", session.login);
			boolean messageToEverybody = (login == null || login.equals("")) && (schema == null || schema.equals(""));
			boolean messageToSchema = (login == null || login.equals("")) && session.schema.equals(schema);
			boolean messageToUser = session.login.equals(login);
			if (messageToEverybody || messageToUser || messageToSchema) {
				logger.fine("firing!");
				CarabiMessage.sendMessage(session.sessionContextChannel, (short) eventCode, message);
			}
		}
	}
}

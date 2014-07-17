package ru.carabi.server.eventer;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.internal.ConcurrentSet;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.json.Json;
import javax.json.JsonObject;
import javax.xml.bind.DatatypeConverter;
import ru.carabi.server.soap.CarabiException_Exception;

/**
 * Контейнер клиентских сессий.
 * При подключении клиента к NettyListener соединение сохраняется тут для дальнейшей
 * передачи пакетов по инициативе сервера.
 * 
 * @author sasha<kopilov.ad@gmail.com>
 */
public class ClientsHolder {
	private static final String key = "Carab!";
	private static final String salt = "EventerKOD";
	private static final String pepper = "#Test~";
	private static final Charset charset = Charset.forName("UTF-8");
	private static final Logger logger = Logger.getLogger(ClientsHolder.class.getName());

	private static final ConcurrentHashMap<String, SessionTimer> sessions = new ConcurrentHashMap<>();
	
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
			String soapToken = decrypt(eventerToken);
			client.getUtilProperties().setProperty("soapToken", soapToken);
			SessionTimer sessionTimer = new SessionTimer(eventerToken, soapToken, client);
			sessions.put(eventerToken, sessionTimer);
			new Thread(sessionTimer).start();
			return true;
		} catch (Exception ex) {
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
	 * Сессия пользователя с таймером для отправки статистических событий.
	 * По таймеру происходит опрос БД и отправка сообщений клиенту.
	 */
	private static class SessionTimer implements Runnable {
		private static final int EVENT_INTERVAL = 5;//интервал таймера проверки событий в секундах
		private static final int SESSION_INTERVAL = 5 * 60;//интервал обновления сессии
		boolean active = true;
		String schema;
		String login;
		int userId;
		String soapToken;
		String eventerToken;
		MessagesHandler client;
		ChannelHandlerContext sessionContextChannel;
		Set<CarabiMessage.Type> whatToSend = new ConcurrentSet<>();//типы событий, которые должны приходить клиенту автоматически
		Map<CarabiMessage.Type, String> oldEvents = new ConcurrentHashMap<>();//события по типам, приходившие клиенту ранее
		
		SessionTimer(String eventerToken, String soapToken, MessagesHandler client) throws CarabiException_Exception {
			active = true;
			this.eventerToken = eventerToken;
			this.soapToken = soapToken;
			this.client = client;
			this.sessionContextChannel = client.getChannel();
			String userInfoJson = SoapGateway.guestServicePort.getUserInfo(soapToken);
			logger.info(userInfoJson);
			JsonObject userInfo = Json.createReader(new StringReader(userInfoJson)).readObject();
			schema = userInfo.getString("schema");
			login = userInfo.getString("login");
			userId = userInfo.getInt("carabiUserID");
		}
		
		@Override
		public synchronized void run() {
			int sessionCounter = 0;//
			while (active && !sessionContextChannel.isRemoved()) {
				//Проверяем наличие требуемых (whatToSend) событий
				for (CarabiMessage.Type type: whatToSend) {
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
				}
				//обмениваемся пингами с клиентом
				CarabiMessage event = CarabiMessage.writeCarabiMessage("TEST_SESSION_PING", CarabiMessage.Type.ping, false, client);
				event.post(eventerToken);
			}
		}
	}
	
	static String encrypt (String data) throws Exception {
		byte[] input = data.getBytes(charset);
		String secretKey = key + salt;
		SecretKeySpec key = new SecretKeySpec(secretKey.getBytes(charset), "AES");
		String iv = salt + pepper;
		IvParameterSpec ivSpec = new IvParameterSpec(iv.getBytes(charset));
		Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec);
		byte[] encrypted= new byte[cipher.getOutputSize(input.length)];
		int enc_len = cipher.update(input, 0, input.length, encrypted, 0);
		enc_len += cipher.doFinal(encrypted, enc_len);
		return DatatypeConverter.printBase64Binary(encrypted);
	}
	
	/**
	 * Расшифровка входного токена.
	 * Если входной токен был получен функцией getEventerToken SOAP-сервера &mdash;
	 * на выходе должен получиться SOAP-токен.
	 * @param encrypted зашифрованный токен
	 * @return расшифрованный токен для обращения к SOAP-серверу
	 * @throws Exception 
	 */
	static String decrypt (String encrypted) throws Exception {
		byte[] input = DatatypeConverter.parseBase64Binary(encrypted);
		String secretKey = key + salt;
		SecretKeySpec key = new SecretKeySpec(secretKey.getBytes(charset), "AES");
		String iv = salt + pepper;
		IvParameterSpec ivSpec = new IvParameterSpec(iv.getBytes(charset));
		Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		cipher.init(Cipher.DECRYPT_MODE, key, ivSpec);
		byte[] decrypted = new byte[cipher.getOutputSize(input.length)];
		int dec_len = cipher.update(input, 0, input.length, decrypted, 0);
		dec_len += cipher.doFinal(decrypted, dec_len);
		return new String(decrypted, charset).trim();
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
	public static void addEventTypes(String eventerToken, Collection<CarabiMessage.Type> eventTypes) {
		sessions.get(eventerToken).whatToSend.addAll(eventTypes);
	}
	/**
	 * Отключить события у пользователя.
	 * @param eventerToken токен пользователя
	 * @param eventTypes типы событий, о которых не оповещать
	 */
	public static void removeEventTypes(String eventerToken, Collection<CarabiMessage.Type> eventTypes) {
		sessions.get(eventerToken).whatToSend.removeAll(eventTypes);
	}
	
	static void clearEventTypes(String eventerToken) {
		sessions.get(eventerToken).whatToSend.clear();
	}
	
	public static void setLastEvent(String eventerToken, CarabiMessage.Type eventType, String eventText) {
		sessions.get(eventerToken).oldEvents.put(eventType, eventText);
	}
	
	public static void fireEvent(String eventPackageJson) throws Exception {
		logger.log(Level.FINE, "fireEvent: {0}", eventPackageJson);
		JsonObject eventPackage = Json.createReader(new StringReader(eventPackageJson)).readObject();
		String schema = eventPackage.getString("schema");
		String login = eventPackage.getString("login");
		int eventcode = eventPackage.getInt("eventcode");
		String message = eventPackage.getString("message");
		for (SessionTimer session: sessions.values()) {
			logger.log(Level.FINE, "messsage to {0}", session.login);
			boolean messageToUser = session.login.equals(login);
			boolean messageToSchema = (login == null || login.equals("")) && session.schema.equals(schema);
			if (messageToUser || messageToSchema) {
				logger.fine("firing!");
				CarabiMessage.sendAnswer(session.sessionContextChannel, (short) eventcode, message);
			}
		}
	}
}

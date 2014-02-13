package ru.carabi.server.eventer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.nio.charset.Charset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;
import ru.carabi.server.eventer.wsdl.GuestService;
import ru.carabi.server.eventer.wsdl.GuestService_Service;

/**
 * Контейнер клиентских сессий.
 * При подключении клиента к NettyListener соединение сохраняется тут для дальнейшей
 * передачи пакетов по инициативе сервера.
 * 
 * @author sasha
 */
public class ClientSessionHolder {
	private static final String key = "Carab!";
	private static final String salt = "EventerKOD";
	private static final String pepper = "#Test~";
	private static final Charset charset = Charset.forName("UTF-8");
	
	private static final ConcurrentHashMap<String, Timer> sessions = new ConcurrentHashMap<>();
	
	/**
	 * Добавить подключение.
	 * Если пользователь авторизован (токен есть в системе) &mdash; сохраннить сессию,
	 * если нет &mdash; отклонить.
	 * @param token токен, полученный функцией getEventerToken SOAP-сервера.
	 * @param sessionContext сохраняемая сессия.
	 * @return была ли сохранена сессия.
	 */
	public static boolean addSession(String token, ChannelHandlerContext sessionContext) {
		try {
			String soapToken = decrypt(token);
			GuestService_Service service = new GuestService_Service();
			GuestService port = service.getPort(GuestService.class);
			Logger.getLogger(ClientSessionHolder.class.getName()).log(Level.INFO, "{0}", port.getOracleUserID(soapToken));
			Timer sessionTimer = new Timer(token, sessionContext);
			sessions.put(token, sessionTimer);
			new Thread(sessionTimer).start();
			return true;
		} catch (Exception ex) {
			Logger.getLogger(ClientSessionHolder.class.getName()).log(Level.INFO, null, ex);
			return false;
		}
	}
//	public static ChannelHandlerContext getSession(String token) {
//		return sessions.get(token);
//	}
	public static void delSession(String token) {
		Timer sessionTimer = sessions.remove(token);
		if (sessionTimer != null) {
			sessionTimer.active = false;
		}
	}
	
	private static class Timer implements Runnable {
		boolean active = true;
		String token;
		ChannelHandlerContext sessionContext;
		
		Timer(String token, ChannelHandlerContext session) {
			active = true;
			this.token = token;
			this.sessionContext = session;
		}

		@Override
		public synchronized void run() {
			while (active && !sessionContext.isRemoved()) {
				try {
					wait(5000);
				} catch (InterruptedException ex) {
					Logger.getLogger(ClientSessionHolder.class.getName()).log(Level.SEVERE, null, ex);
				}
				sendPing();
			}
		}
		
		private void sendPing() {
			String answer = "PING ПИНГ";
			byte[] dataToPost = answer.getBytes(Charset.forName("UTF-8"));
			ByteBuf data = sessionContext.alloc().buffer(dataToPost.length + 3);
//			data.writeShort(CarabiMessage.Type.ping.getCode());
			data.writeByte(CarabiMessage.Type.ping.getCode());
			data.writeByte(0);
			data.writeBytes(dataToPost);
			data.writeByte(0);
			sessionContext.writeAndFlush(data);
		}
	}
	private static String decrypt (String encrypted) throws Exception {
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
}

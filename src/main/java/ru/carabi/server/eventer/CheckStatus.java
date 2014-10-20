package ru.carabi.server.eventer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;
import ru.carabi.libs.CarabiEventType;

/**
 * Клиент, отправляющий запросы для проверки состояния Eventer-а
 * @author sasha
 */
public class CheckStatus{
	public static void main(String[] args) {
		ResourceBundle settings = Main.settings;
		int port = Integer.parseInt(settings.getString("LISTEN_PORT"));
		try (Socket socket = new Socket("127.0.0.1", port)) {
			OutputStream outputStream = socket.getOutputStream();
			InputStream inputStream = socket.getInputStream();
			sendPing(outputStream);
			assert "PONG ПОНГ".equals(getPongResponse(inputStream));
			System.out.println("Eventer running");
		} catch (Exception ex) {
			Logger.getLogger(CheckStatus.class.getName()).log(Level.SEVERE, null, ex);
			System.exit(1);
		}
	}

	private static void sendPing(OutputStream outputStream) throws IOException {
		outputStream.write((byte)0);
		outputStream.write((byte)CarabiEventType.ping.getCode());
		outputStream.write("PING ПИНГ".getBytes("UTF-8"));
		outputStream.write((byte)0);
	}

	private static String getPongResponse(InputStream inputStream) throws IOException {
		int read;
		byte[] buffer = new byte[10240];
		read = inputStream.read();
		assert read == 0;
		read = inputStream.read();
		assert read == CarabiEventType.pong.getCode();
		int i = 0;
		read = inputStream.read();
		while (read != 0) {
			buffer[i] = (byte) read;
			read = inputStream.read();
			i++;
		}
		return new String(buffer);
	}
}

package ru.carabi.server.eventer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Клиент, отправляющий запросы для закрытия Eventer-а
 * @author sasha
 */
public class ShutdownEventer{
	private static final ResourceBundle settings = ResourceBundle.getBundle("ru.carabi.server.eventer.Settings");
	public static void main(String[] args) {
		int port = Integer.parseInt(settings.getString("LISTEN_PORT"));
		try (Socket socket = new Socket("127.0.0.1", port)) {
			OutputStream outputStream = socket.getOutputStream();
			InputStream inputStream = socket.getInputStream();
			emptyShutdownRequest(outputStream);
			String key = getResponse(inputStream);
			String encryptedKey = ClientsHolder.encrypt(key);
			encryptedKeyRequest(outputStream, encryptedKey);
			assert "shutdownOK".equals(getResponse(inputStream));
			System.out.println("Shutdown OK");
		} catch (Exception ex) {
			Logger.getLogger(ShutdownEventer.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	private static void encryptedKeyRequest(OutputStream outputStream, String encryptedKey) throws IOException {
		outputStream.write((byte)0);
		outputStream.write((byte)CarabiMessage.Type.shutdown.getCode());
		byte[] buffer = encryptedKey.getBytes();
		for (int i=0; i<buffer.length; i++) {
			outputStream.write(buffer[i]);
		}
		outputStream.write((byte)0);
	}

	private static String getResponse(InputStream inputStream) throws IOException {
		int read;
		byte[] buffer = new byte[10240];
		read = inputStream.read();
		assert read == 0;
		read = inputStream.read();
		assert read == CarabiMessage.Type.shutdown.getCode();
		int i = 0;
		read = inputStream.read();
		while (read != 0) {
			buffer[i] = (byte) read;
			read = inputStream.read();
			i++;
		}
		return new String(buffer);
	}

	private static void emptyShutdownRequest(OutputStream outputStream) throws IOException {
		outputStream.write((byte)0);
		outputStream.write((byte)CarabiMessage.Type.shutdown.getCode());
		outputStream.write((byte)0);
	}
}

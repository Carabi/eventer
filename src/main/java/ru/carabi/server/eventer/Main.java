package ru.carabi.server.eventer;

import java.util.ResourceBundle;

/**
 * Главный класс Carabi Eventer.
 * Парсинг параметров и запуск {@link TCPNettyListener}
 * @author sasha
 */
public class Main {
	private static final ResourceBundle settings = ResourceBundle.getBundle("ru.carabi.server.eventer.Settings");
	public static void main(String[] args) {
		int port = Integer.parseInt(settings.getString("LISTEN_PORT"));
		ClientSessionHolder.setSoapServer(settings.getString("SOAP_SERVER"));
		try {
			if (args.length == 1) {
				setSoapServer(args[0]);
			} else if (args.length == 2) {
				setSoapServer(args[0]);
				port = Integer.parseInt(args[1]);
			} else if (args.length > 2) {
				printHelp();
				return;
			}
		} catch (IllegalArgumentException e) {
			printHelp();
			return;
		}
		new TCPNettyListener().start(port);
	}

	private static void printHelp() {
		System.out.println("Usage: java ru.carabi.server.eventer.Main [http://server/soap_service/ [listen_port]]\nDefault are:\n" + settings.getString("SOAP_SERVER") + "\n" + settings.getString("LISTEN_PORT"));
	}

	private static void setSoapServer(String soapServer) throws IllegalArgumentException{
		if (soapServer.startsWith("http://") || soapServer.startsWith("https://")) {
			ClientSessionHolder.setSoapServer(soapServer);
		} else throw new IllegalArgumentException();
	}
}

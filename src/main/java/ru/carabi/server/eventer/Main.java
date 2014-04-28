package ru.carabi.server.eventer;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ResourceBundle;

/**
 * Главный класс Carabi Eventer.
 * Парсинг параметров и запуск {@link TCPNettyListener}
 * @author sasha
 */
public class Main {
	private static final ResourceBundle settings = ResourceBundle.getBundle("ru.carabi.server.eventer.Settings");
	private static String useSoapServer;
	public static void main(String[] args) {
		int port = Integer.parseInt(settings.getString("LISTEN_PORT"));
		useSoapServer = settings.getString("SOAP_SERVER");
		
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
		try {
			SoapGateway.init(useSoapServer);
			new TCPNettyListener().start(port);
		} catch (Exception ex) {
			System.out.println("error: " + ex.getMessage());
		}
	}

	private static void printHelp() {
		System.out.println("Usage: java ru.carabi.server.eventer.Main [http://server/soap_service/ [listen_port]]\nDefault are:\n" + settings.getString("SOAP_SERVER") + "\n" + settings.getString("LISTEN_PORT"));
	}

	private static void setSoapServer(String soapServerInput) throws IllegalArgumentException{
		try {
			new URL(soapServerInput);
			useSoapServer = soapServerInput;
		} catch(MalformedURLException e) {
			throw new IllegalArgumentException(e);
		}
	}
}

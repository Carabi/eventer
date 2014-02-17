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
		int port = Integer.parseInt(settings.getString("PORT"));
		if (args.length == 1) {
			try {
				port = Integer.parseInt(args[0]);
			} catch (NumberFormatException e) {
				printHelp();
				return;
			}
		} else if (args.length > 1) {
			printHelp();
			return;
		}
		new TCPNettyListener().start(port);
	}

	private static void printHelp() {
		System.out.println("Usage: java ru.carabi.server.eventer.Main [port]\nDefault port is " + settings.getString("PORT"));
	}
	
}

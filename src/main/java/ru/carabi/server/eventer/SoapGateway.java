package ru.carabi.server.eventer;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ResourceBundle;
import ru.carabi.server.soap.GuestService;
import ru.carabi.server.soap.GuestService_Service;
import ru.carabi.server.soap.MessageService;
import ru.carabi.server.soap.MessageService_Service;
import ru.carabi.server.soap.QueryService;
import ru.carabi.server.soap.QueryService_Service;

/**
 *
 * @author sasha
 */
public class SoapGateway {
	private static final ResourceBundle settings = ResourceBundle.getBundle("ru.carabi.server.eventer.Settings");
	
	static GuestService guestServicePort;
	static MessageService messageServicePort;
	static QueryService queryServicePort;
	
	public static void init(String soapServer) throws MalformedURLException {
		GuestService_Service guestService = new GuestService_Service(new URL(soapServer + settings.getString("GUEST_SERVICE")));
		guestServicePort = guestService.getPort(GuestService.class);
		MessageService_Service messageService = new MessageService_Service(new URL(soapServer + settings.getString("MESSAGE_SERVICE")));
		messageServicePort = messageService.getPort(MessageService.class);
		QueryService_Service queryService = new QueryService_Service(new URL(soapServer + settings.getString("QUERY_SERVICE")));
		queryServicePort = queryService.getPort(QueryService.class);
	}
}

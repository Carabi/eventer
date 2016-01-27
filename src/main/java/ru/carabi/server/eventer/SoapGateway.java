package ru.carabi.server.eventer;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ResourceBundle;
import ru.carabi.stub.ChatService;
import ru.carabi.stub.ChatService_Service;
import ru.carabi.stub.GuestService;
import ru.carabi.stub.GuestService_Service;
import ru.carabi.stub.MessageService;
import ru.carabi.stub.MessageService_Service;
import ru.carabi.stub.QueryService;
import ru.carabi.stub.QueryService_Service;

/**
 *
 * @author sasha
 */
public class SoapGateway {
	
	static ChatService chatServicePort;
	static GuestService guestServicePort;
	static MessageService messageServicePort;
	static QueryService queryServicePort;
	
	public static void init(String soapServer) throws MalformedURLException {
		ResourceBundle settings =Main.settings;
		ChatService_Service chatService = new ChatService_Service(new URL(soapServer + settings.getString("CHAT_SERVICE")));
		chatServicePort = chatService.getChatServicePort();
		GuestService_Service guestService = new GuestService_Service(new URL(soapServer + settings.getString("GUEST_SERVICE")));
		guestServicePort = guestService.getPort(GuestService.class);
		MessageService_Service messageService = new MessageService_Service(new URL(soapServer + settings.getString("MESSAGE_SERVICE")));
		messageServicePort = messageService.getPort(MessageService.class);
		QueryService_Service queryService = new QueryService_Service(new URL(soapServer + settings.getString("QUERY_SERVICE")));
		queryServicePort = queryService.getPort(QueryService.class);
	}
}

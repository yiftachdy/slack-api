package allbegray.slack.rtm;

import allbegray.slack.exception.SlackException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.ws.DefaultWebSocketListener;
import org.asynchttpclient.ws.WebSocket;
import org.asynchttpclient.ws.WebSocketUpgradeHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.asynchttpclient.Dsl.*;

public class SlackRealTimeMessagingClient {

	private static Log logger = LogFactory.getLog(SlackRealTimeMessagingClient.class);

	private String webSocketUrl;
	private ProxyServerInfo proxyServerInfo;
	private AsyncHttpClient asyncHttpClient;
	private WebSocket webSocket;
	private Map<String, List<EventListener>> listeners = new HashMap<String, List<EventListener>>();
	private boolean stop;
	private ObjectMapper mapper;

	public SlackRealTimeMessagingClient(String webSocketUrl, ObjectMapper mapper) {
		this(webSocketUrl, null, mapper);
	}

	public SlackRealTimeMessagingClient(String webSocketUrl, ProxyServerInfo proxyServerInfo, ObjectMapper mapper) {
		if (mapper == null) {
			mapper = new ObjectMapper();
		}
		this.webSocketUrl = webSocketUrl;
		this.proxyServerInfo = proxyServerInfo;
		this.mapper = mapper;
	}
	
	public void addListener(Event event, EventListener listener) {
		addListener(event.name().toLowerCase(), listener);
	}

	public void addListener(String event, EventListener listener) {
		List<EventListener> eventListeners = listeners.get(event);
		if (eventListeners == null) {
			eventListeners = new ArrayList<EventListener>();
			listeners.put(event, eventListeners);
		}
		eventListeners.add(listener);
	}

	public void close() {
		stop = true;
		if (webSocket != null && webSocket.isOpen()) {
			try {
				webSocket.close();
			} catch (IOException e) {
				throw new SlackException(e);
			}
		}
		if (asyncHttpClient != null && !asyncHttpClient.isClosed()) {
			try {
				asyncHttpClient.close();
			} catch (IOException e) {
				throw new SlackException(e);
			}
		}
	}

	public boolean connect() {
		try {
			asyncHttpClient = proxyServerInfo != null ? asyncHttpClient(config().setProxyServer(proxyServer(proxyServerInfo.getHost(), proxyServerInfo.getPort()))) : asyncHttpClient();
			BoundRequestBuilder requestBuilder = asyncHttpClient.prepareGet(webSocketUrl);
			webSocket = requestBuilder.execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new DefaultWebSocketListener() {

				@Override
				public void onMessage(String message) {
					String type = null;
					JsonNode node = null;
					try {
						node = mapper.readTree(message);
						type = node.findPath("type").asText();
					} catch (Exception e) {
						logger.error(e);
					}

					if (!"pong".equals(type)) {
						logger.info("Slack RTM message : " + message);
					}

					if (type != null) {
						List<EventListener> eventListeners = listeners.get(type);
						if (eventListeners != null && !eventListeners.isEmpty()) {
							for (EventListener listener : eventListeners) {
								listener.handleMessage(node);
							}
						}
					}
				}

				@Override
				public void onClose(WebSocket websocket) {
					super.onClose(websocket);
					stop = true;
				}

				@Override
				public void onError(Throwable t) {
					throw new SlackException(t);
				}

			}).build()).get();

			logger.info("connected Slack RTM(Real Time Messaging) server : " + webSocketUrl);

			await();

		} catch (Exception e) {
			close();
			throw new SlackException(e);
		}
		return true;
	}

	private long socketId = 1;

	private void ping() {
		ObjectNode pingMessage = mapper.createObjectNode();
		pingMessage.put("id", ++socketId);
		pingMessage.put("type", "ping");
		String pingJson = pingMessage.toString();
		webSocket.sendMessage(pingJson);

		logger.debug("ping : " + pingJson);
	}

	private void await() {
		Thread thread = new Thread(new Runnable() {
			@Override
			public void run() {
				while (!stop) {
					try {
						ping();
						Thread.sleep(3 * 1000);
					} catch (Exception e) {
						throw new SlackException(e);
					}
				}
			}
		});
		thread.start();
	}

}
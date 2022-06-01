package com.fngtps.wowza.tube;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.stream.Collectors;

import javax.xml.bind.DatatypeConverter;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.wowza.wms.amf.AMFDataList;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.client.IClient;
import com.wowza.wms.module.IModuleOnConnect;
import com.wowza.wms.module.ModuleBase;
import com.wowza.wms.request.RequestFunction;

// Sends callbacks to SWITCHtube when RTMP streams connect for authorization, configuration, and state updates. 
public class Callbacks extends ModuleBase implements IModuleOnConnect {
	// Raised when the callback failed due to connection or configuration issues.
	public class CallbackFailed extends Exception {
		private static final long serialVersionUID = 4512390269759546454L;

		public CallbackFailed(String errorMessage) {
			super(errorMessage);
		}
	}

	// Raised when the callback failed because the server returned a forbidden
	// result.
	public class CallbackForbidden extends Exception {
		private static final long serialVersionUID = 1815794114707181170L;

		public CallbackForbidden(String errorMessage) {
			super(errorMessage);
		}
	}

	// Details sent by SWITCHtube about a channel's stream.
	public class Stream {
		// Suggested name of the stream.
		private String name;
		// Suggested name of the broadcast.
		@SerializedName("broadcast_name")
		private String broadcastName;

		Stream() {
		}
	}

	// Callback details sent to SWITCHtube.
	public class Event {
		@SuppressWarnings("unused")
		private String name;
		@SuppressWarnings("unused")
		private String secret;
		@SuppressWarnings("unused")
		private String status;

		Event() {
		}
	}

	// Utility class to compute a signature for callbacks.
	public class Signature {

		private String serialized;

		public Signature(String secret, String newt, String payload) {
			// Newt is a one-use token to add some entropy to the signature. The secret is a
			// shared secret between Wowza and SWITCHtube.
			this.serialized = newt + "\n" + secret + "\n" + payload + "\n";
		}

		public String toString() {
			try {
				return DatatypeConverter.printHexBinary(getHash()).toLowerCase();
			} catch (NoSuchAlgorithmException e) {
				e.printStackTrace();
				return "";
			}
		}

		private byte[] getHash() throws NoSuchAlgorithmException {
			MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
			return messageDigest.digest(this.serialized.getBytes(StandardCharsets.UTF_8));
		}

	}

	// Client class to send callbacks to SWITCHtube.
	public class Reporter {

		private URL callbackUrl;
		private String callbackSecret;

		public Reporter(String callbackUrl, String callbackSecret) throws MalformedURLException {
			this.callbackUrl = new URL(callbackUrl);
			this.callbackSecret = callbackSecret;
		}

		public URL getCallbackUrl() {
			return callbackUrl;
		}

		public Stream performConnectCallback(String streamName) throws CallbackFailed, CallbackForbidden {
			Event event = new Event();
			event.name = streamName;
			event.status = "connected";
			return performCallbackWithRetries(event);
		}

		public Stream performPublishedCallback(String streamName, String streamSecret)
				throws CallbackFailed, CallbackForbidden {
			Event event = new Event();
			event.name = streamName;
			event.secret = streamSecret;
			event.status = "published";
			return performCallbackWithRetries(event);
		}

		public Stream performDisconnectedCallback(String streamName) throws CallbackFailed, CallbackForbidden {
			Event event = new Event();
			event.name = streamName;
			event.status = "stopped";
			return performCallbackWithRetries(event);
		}

		public Stream performCallbackWithRetries(Event event) throws CallbackFailed, CallbackForbidden {
			// One original request and 2 retries.
			int retries = 2;
			while (true) {
				try {
					return performCallback(event);
				} catch (CallbackFailed e) {
					System.err.println(e.getLocalizedMessage());
					if (--retries < 0)
						throw e;
					try {
						Thread.sleep(500);
					} catch (InterruptedException ex) {
						ex.printStackTrace();
					}
				}
			}
		}

		// Send a callback to SWITCHtube for a stream authenticated by the stream
		// secret. Returns a stream name suggested by SWITCHtube.
		public Stream performCallback(Event event) throws CallbackFailed, CallbackForbidden {
			Gson gson = new Gson();
			String payload = gson.toJson(event);

			String newt = generateNewt();
			Signature signature = new Signature(callbackSecret, newt, payload);

			HttpURLConnection connection;
			try {
				connection = (HttpURLConnection) callbackUrl.openConnection();
			} catch (IOException e) {
				throw new CallbackFailed(
						"Failed to open connection to " + callbackUrl + ": " + e.getLocalizedMessage());
			}

			// Raises a protocol exception because the request method can technically be
			// dynamic. In our case that would be a compile-time issue.
			try {
				connection.setRequestMethod("POST");
			} catch (ProtocolException e) {
				throw new CallbackFailed("HttpURLConnection can't POST requests: " + e.getLocalizedMessage());
			}

			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", "application/json");
			connection.setRequestProperty("Accept", "application/json");
			connection.setRequestProperty("X-Newt", newt);
			connection.setRequestProperty("X-Signature", signature.toString());

			try {
				OutputStream requestStream = connection.getOutputStream();
				requestStream.write(payload.getBytes("UTF-8"));
				requestStream.close();
			} catch (java.io.IOException e) {
				throw new CallbackFailed("Failed to set payload for the callback request: " + e.getLocalizedMessage());
			}

			try {
				int responseCode = connection.getResponseCode();
				if (responseCode == 403) {
					throw new CallbackForbidden("Callback request returned a forbidden status code.");
				}
			} catch (IOException e) {
				throw new CallbackFailed("Failed to perform callback request: " + e.getLocalizedMessage());
			}

			try {
				String json = new BufferedReader(
						new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)).lines()
						.collect(Collectors.joining("\n"));
				try {
					Stream stream = gson.fromJson(json, Stream.class);
					return stream;
				} catch (JsonSyntaxException e) {
					System.out.println(json);
					throw new CallbackFailed("Failed to parse JSON response: " + e.getLocalizedMessage());
				}
			} catch (IOException e) {
				throw new CallbackFailed("Failed to read callback response body: " + e.getLocalizedMessage());
			}
		}

		private String generateNewt() {
			SecureRandom random = new SecureRandom();
			return DatatypeConverter.printHexBinary(random.generateSeed(6));
		}
	}

	private Reporter reporter;

	public void onAppStart(IApplicationInstance appInstance) {
		String callbackUrl = appInstance.getProperties().getPropertyStr("SWITCHtubeCallbackUrl");
		try {
			this.reporter = new Reporter(callbackUrl,
					appInstance.getProperties().getPropertyStr("SWITCHtubeCallbackSecret"));
			getLogger().info("Using " + reporter.getCallbackUrl() + " to send callbacks to SWITCHtube.");
		} catch (MalformedURLException e) {
			getLogger().info("The configured URL " + callbackUrl + " is malformed, callbacks are disabled. "
					+ e.getLocalizedMessage());
		}
	}

	@Override
	public void onConnect(IClient client, RequestFunction function, AMFDataList params) {
		if (this.reporter == null) {
			getLogger().info("Rejected connection because the callback is not configured properly.");
			client.rejectConnection();
		} else {
			// Report the intent to connect a stream using its stream name and use the
			// callback response to determine if the client is allowed to connect.
			IApplicationInstance applicationInstance = getAppInstance(client);
			String streamName = applicationInstance.getName();

			if (streamName == null || streamName.isEmpty() || streamName == "_definst_") {
				getLogger().info("Application instance did not return a useful stream name: " + streamName);
				client.rejectConnection();
			}
			getLogger().info("Performing connect callback with stream name: " + streamName);

			try {
				Stream stream = reporter.performConnectCallback(streamName);
				if (stream == null) {
					getLogger().info("Connection rejected because stream could not be found.");
					client.rejectConnection();
				} else {
					getLogger().info("Allowing connection for stream with name: " + stream.name);
				}
			} catch (CallbackFailed e) {
				getLogger().info("Connection rejected because callback failed: " + e.getLocalizedMessage());
				client.rejectConnection();
			} catch (CallbackForbidden e) {
				getLogger().info("Connection rejected because callback was forbidden: " + e.getLocalizedMessage());
				client.rejectConnection();
			}
		}
	}

	public void publish(IClient client, RequestFunction function, AMFDataList params) {
		if (this.reporter == null)
			return;

		// Report the intent to publish a stream using its stream name and stream key.
		// Use the response status to determine authorization to publish the stream. Use
		// the returned stream name as the actual stream name.
		IApplicationInstance applicationInstance = getAppInstance(client);
		// The stream secret (aka. stream key) will be in the params if set.
		String streamSecret = params.getString(PARAM1);
		// When the client did not provide a stream secret we try to use the query part
		// of the URL.
		if (streamSecret == null || streamSecret.isEmpty()) {
			getLogger().info("Stream secret is not present, attempting to use query part of the connection URL.");
			streamSecret = client.getQueryStr();
		}
		getLogger().info("Using stream secret: " + streamSecret);
		try {
			Stream stream = reporter.performPublishedCallback(applicationInstance.getName(), streamSecret);
			getLogger().info("Using broadcast name: " + stream.broadcastName);
			// Wowza will use the stream key in the public URL for the playlist if we don't
			// set this parameter. By setting the parameter the URL will start with
			// /{applicationName/{streamName}/{streamBroadcastName}
			params.set(PARAM1, stream.broadcastName);
		} catch (CallbackFailed e) {
			getLogger().info("Stream stopped because callback failed: " + e.getLocalizedMessage());
			client.shutdownClient();
		} catch (CallbackForbidden e) {
			getLogger().info("Stream stopped because callback was forbidden: " + e.getLocalizedMessage());
			client.shutdownClient();
		}

		invokePrevious(client, function, params);
	}

	@Override
	public void onConnectAccept(IClient client) {
	}

	@Override
	public void onConnectReject(IClient client) {
	}

	@Override
	public void onDisconnect(IClient client) {
		if (this.reporter == null)
			return;

		// Let SWITCHtube know the stream disconnected.
		IApplicationInstance applicationInstance = getAppInstance(client);
		try {
			reporter.performDisconnectedCallback(applicationInstance.getName());
		} catch (CallbackFailed | CallbackForbidden e) {
			getLogger().info("Failed to report disconnection: " + e.getLocalizedMessage());
		}
	}

}

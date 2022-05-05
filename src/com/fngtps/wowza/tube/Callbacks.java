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
		// Name of the stream, such that the playlist URL in Wowza does not contain a
		// stream secret.
		private String name;

		Stream() {
		}
	}

	// Callback details sent to SWITCHtube.
	public class Event {
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

		// Send a callback to SWITCHtube for a stream authenticated by the stream
		// secret. Returns a stream name suggested by SWITCHtube.
		public String performCallback(String streamSecret, String status) throws CallbackFailed, CallbackForbidden {
			Gson gson = new Gson();

			Event event = new Event();
			event.secret = streamSecret;
			event.status = status;
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
					return stream.name;
				} catch (JsonSyntaxException e) {
					System.out.println(json);
					throw new CallbackFailed("Failed to parse JSON response: " + e.getLocalizedMessage());
				}
			} catch (IOException e) {
				throw new CallbackFailed("Failed to read callback response body: " + e.getLocalizedMessage());
			}
		}

		public String performCallbackWithRetries(String streamSecret, String status)
				throws CallbackFailed, CallbackForbidden {
			int retries = 2;
			while (true) {
				try {
					return performCallback(streamSecret, status);
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
			// Make sure the stream secret is value during connect because it's the only
			// time we can reject the connection.
			String streamSecret = client.getQueryStr();
			try {
				String streamName = reporter.performCallbackWithRetries(streamSecret, "connected");
				if (streamName.isEmpty()) {
					getLogger().info("Connection rejected because stream name is emtpy.");
					client.rejectConnection();
				} else {
					getLogger().info("Allowing connection for stream with name: " + streamName);
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

		// Force the stream name to the name suggested by SWITCHtube so a broadcaster
		// can't create streams with random names.
		String streamSecret = client.getQueryStr();
		try {

			String streamName = reporter.performCallbackWithRetries(streamSecret, "published");
			if (streamName.isEmpty()) {
				getLogger().info("Stop the stream because the stream name is empty.");
				client.rejectConnection();
			} else {
				getLogger().info("Using stream name: " + streamName);
				params.set(PARAM1, streamName);
			}
		} catch (CallbackFailed e) {
			getLogger().info("Stream stopped because callback failed: " + e.getLocalizedMessage());
			client.rejectConnection();
		} catch (CallbackForbidden e) {
			getLogger().info("Stream stopped because callback was forbidden: " + e.getLocalizedMessage());
			client.rejectConnection();
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
		String streamSecret = client.getQueryStr();
		try {
			reporter.performCallbackWithRetries(streamSecret, "stopped");
		} catch (CallbackFailed | CallbackForbidden e) {
			getLogger().info("Failed to report disconnection: " + e.getLocalizedMessage());
		}
	}

}

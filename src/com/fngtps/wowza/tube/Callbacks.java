package com.fngtps.wowza.tube;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
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

public class Callbacks extends ModuleBase implements IModuleOnConnect {
	private Reporter reporter;
	
	public class Stream {
		private String name;
		Stream() {
		}
	}
	
	public class Event {
		private String secret;
		private String status;
		Event() {
		}
	}
	
	public class Signature {
		
		private String serialized;
		
		public Signature(String secret, String newt, String payload) {
			this.serialized = newt + "\n" + secret + "\n" + payload + "\n";
		}

		public String toString() {
			try {
				return DatatypeConverter.printHexBinary(getHash());
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
	
	public class Reporter {
		
		private String callbackUrl;
		private String callbackSecret;

		public Reporter(String callbackUrl, String callbackSecret) {
			this.callbackUrl = callbackUrl;
			this.callbackSecret = callbackSecret;
		}
		
		public String getCallbackUrl() {
			return callbackUrl;
		} 
		
		public String performCallback(String streamSecret, String status) throws MalformedURLException {
			Gson gson = new Gson();

			Event event = new Event();
			event.secret = streamSecret;
			event.status = status;
			String payload = gson.toJson(event);

			String newt = generateNewt();
			Signature signature = new Signature(this.callbackSecret, newt, payload);

			try {
				URL url = new URL(this.callbackUrl);
				HttpURLConnection connection = (HttpURLConnection) url.openConnection();
				connection.setDoOutput(true);
				connection.setDoInput(true);
				connection.setRequestMethod("POST");
				connection.setRequestProperty("Content-Type", "application/json");
				connection.setRequestProperty("Accept", "application/json");
				connection.setRequestProperty("X-Newt", newt);
				connection.setRequestProperty("X-Signature", signature.toString().toLowerCase());

				OutputStream requestStream = connection.getOutputStream();
				requestStream.write(payload.getBytes("UTF-8"));
				requestStream.close();
				
				String json = new BufferedReader(
					      new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))
					        .lines()
					        .collect(Collectors.joining("\n"));
				try {
					Stream stream = gson.fromJson(json, Stream.class);
					return stream.name;
				} catch (JsonSyntaxException e) {
					System.out.println(json);
					e.printStackTrace();
					return "";
				}
			} catch (IOException e) {
				e.printStackTrace();
				return "";
			}
		}

		private String generateNewt() {
			SecureRandom random = new SecureRandom();
			return DatatypeConverter.printHexBinary(random.generateSeed(6));
		}

	}
	
	public void onAppStart(IApplicationInstance appInstance) {
		this.reporter = new Reporter(
			appInstance.getProperties().getPropertyStr("SWITCHtubeCallbackUrl"),
			appInstance.getProperties().getPropertyStr("SWITCHtubeCallbackSecret")
		);
		getLogger().info("Using " + reporter.getCallbackUrl() + " to send callbacks to SWITCHtube.");
	}

	@Override
	public void onConnect(IClient client, RequestFunction function, AMFDataList params) {
		String streamSecret = client.getQueryStr();
		try {
			reporter.performCallback(streamSecret, "connected");
		} catch (MalformedURLException e) {
			e.printStackTrace();
			client.rejectConnection("Failed to authorize connection because callback failed.");
		}	
	}

	public void publish(IClient client, RequestFunction function, AMFDataList params) {
		String streamSecret = client.getQueryStr();
		try {
			String streamName = reporter.performCallback(streamSecret, "published");
			getLogger().info("Callback suggests stream name " + streamName);
			params.set(PARAM1, streamName);
		} catch (MalformedURLException e) {
			e.printStackTrace();
			client.rejectConnection("Failed set steam name because callback failed.");
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
	}

}

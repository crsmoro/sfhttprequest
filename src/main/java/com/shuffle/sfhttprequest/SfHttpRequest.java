package com.shuffle.sfhttprequest;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.StatusLine;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.cookie.Cookie;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.ByteArrayBody;
import org.apache.http.entity.mime.content.ContentBody;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.LaxRedirectStrategy;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.apache.http.message.BasicNameValuePair;

public class SfHttpRequest implements Cloneable {

	private static final transient Log log = LogFactory.getLog(SfHttpRequest.class);

	private String url;

	private CredentialsProvider credentialsProvider;

	private String httpMethod = "GET";

	private CloseableHttpClient httpClient;

	private CloseableHttpResponse response;

	private List<NameValuePair> parameters = new ArrayList<>();
	
	private Map<String, ContentBody> multipartParameters = new HashMap<>();

	private BasicCookieStore cookieStore;

	private HttpEntity httpEntity;

	private String httpEntityString;
	
	private byte[] httpEntityFile;
	
	private StatusLine statusLine;
	
	public SfHttpRequest() {
		cookieStore = new BasicCookieStore();
		parameters = new ArrayList<>();
	}

	public SfHttpRequest(String url) {
		this();
		this.url = url;
	}

	public String getUrl() {
		return url;
	}

	public SfHttpRequest setUrl(String url) {
		this.url = url;
		return this;
	}

	public SfHttpRequest addCredentials(CredentialsProvider credentialsProvider, String user, String passowrd) {
		this.credentialsProvider = credentialsProvider;
		this.credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(user, passowrd));
		return this;
	}

	public SfHttpRequest addCredentials(String user, String passowrd) {
		addCredentials(new BasicCredentialsProvider(), user, passowrd);
		return this;
	}

	public SfHttpRequest setHttpMethod(String httpMethod) {
		this.httpMethod = httpMethod;
		return this;
	}

	public SfHttpRequest request() {
		httpClient = HttpClients.custom().setDefaultCookieStore(cookieStore).setRedirectStrategy(new LaxRedirectStrategy()).setDefaultCredentialsProvider(credentialsProvider).build();
		HttpUriRequest httpUriRequest = null;
		if (httpMethod.equals("GET")) {
			httpUriRequest = makeGETRequest();
		} else if (httpMethod.equals("POST")) {
			httpUriRequest = makePOSTRequest();
		}
		else {
			throw new IllegalArgumentException("Http Method not yet implemented");
		}
		addDefaultHeaders(httpUriRequest);
		try {
			response = httpClient.execute(httpUriRequest);
			log.trace("Executing request " + httpUriRequest.getRequestLine());
			log.trace("----------------------------------------");
			statusLine = response.getStatusLine();
			log.trace(statusLine);
			httpEntity = response.getEntity();
			ContentType contentType = ContentType.get(httpEntity);
			ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
			copyBytes(httpEntity.getContent(), byteArrayOutputStream);
			httpEntityFile = byteArrayOutputStream.toByteArray();
			if (contentType.getCharset() != null) {
				httpEntityString = new String(httpEntityFile, contentType.getCharset());
			}
			else {
				httpEntityString = new String(httpEntityFile);
			}
		} catch (IOException e) {
			if (e instanceof SocketTimeoutException) {
				TimeoutException exception = new TimeoutException(e.getMessage());
				exception.addSuppressed(e);
				throw exception;
			}
			else {
				throw new RuntimeException(e);
			}
			
		} finally {
			try {
				httpClient.close();
			} catch (IOException e) {
				log.warn("Problem closing http client", e);
			}
		}
		return this;
	}

	private void addDefaultHeaders(HttpUriRequest httpUriRequest) {
		httpUriRequest.setHeader("User-Agent", "Firefox/60.0.1");
		httpUriRequest.addHeader("Accept-Encoding", "gzip");
		httpUriRequest.addHeader("Accept-Charset", "utf-8");
	}

	public SfHttpRequest addParameter(String key, String value) {
		parameters.add(new BasicNameValuePair(key, value));
		return this;
	}
	
	public SfHttpRequest addParameter(String key, byte[] content) {
		addParameter(key, content, ContentType.DEFAULT_BINARY, null);
		return this;
	}
	
	public SfHttpRequest addParameter(String key, byte[] content, ContentType contentType, String filename) {
		multipartParameters.put(key, new ByteArrayBody(content, contentType, filename));
		return this;
	}
	
	public SfHttpRequest addParameter(String key, InputStream inputStream) {
		addParameter(key, inputStream, ContentType.DEFAULT_BINARY, null);
		return this;
	}
	
	public SfHttpRequest addParameter(String key, InputStream inputStream, ContentType contentType, String filename) {
		//FIXME find way to use inputstream isRepeatable problem
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		copyBytes(inputStream, byteArrayOutputStream);
		multipartParameters.put(key, new ByteArrayBody(byteArrayOutputStream.toByteArray(), contentType, filename));
		return this;
	}
	
	public SfHttpRequest addParameter(String key, File file) {
		addParameter(key, file, ContentType.DEFAULT_BINARY, null);
		return this;
	}
	
	public SfHttpRequest addParameter(String key, File file, ContentType contentType, String filename) {
		multipartParameters.put(key, new FileBody(file, contentType, filename));
		return this;
	}

	/**
	 * For viewing purposes only<br>
	 * Return all parameters<br>
	 * To clear parameters use {@link #clearParameters()}
	 * @return
	 */
	public Map<String, Object> getParameters() {
		Map<String, Object> parameters = new HashMap<>();
		parameters.putAll(this.parameters.stream().collect(Collectors.toMap(NameValuePair::getName, NameValuePair::getValue)));
		parameters.putAll(multipartParameters);
		return Collections.unmodifiableMap(parameters);
	}
	
	public final Object getParameter(String name) {
		Object value = parameters.stream().filter(p -> p.equals(name)).findFirst().orElse(null);
		if (value == null) {
			value = multipartParameters.get(name);
		}
		return value;
	}
	
	public SfHttpRequest clearParameters() {
		parameters.clear();
		multipartParameters.clear();
		return this;
	}

	public SfHttpRequest addCookie(String name, String value) {
		BasicClientCookie cookie = new BasicClientCookie(name, value);
		cookie.setPath("/");
		cookie.setDomain(getDomain(url));
		cookieStore.addCookie(cookie);
		return this;
	}
	
	public SfHttpRequest addCookie(Cookie cookie) {
		cookieStore.addCookie(cookie);
		return this;
	}
	
	public SfHttpRequest addCookies(Collection<Cookie> cookies) {
		cookies.forEach(this::addCookie);
		return this;
	}
	
	public SfHttpRequest addCookies(Cookie[] cookie) {
		cookieStore.addCookies(cookie);
		return this;
	}

	public List<Cookie> getCookies() {
		return cookieStore.getCookies();
	}

	public BasicCookieStore getCookieStore() {
		return cookieStore;
	}

	private HttpUriRequest makeGETRequest() {
		HttpGet httpGet = new HttpGet(this.url + (!this.url.endsWith("?") ? "?" : "") + URLEncodedUtils.format(parameters, "UTF-8"));
		return httpGet;
	}

	private HttpUriRequest makePOSTRequest() {
		HttpPost httpPost = new HttpPost(this.url);
		
		HttpEntity httpEntity = null;
		if (multipartParameters.isEmpty()) {
			httpEntity = new UrlEncodedFormEntity(parameters, StandardCharsets.UTF_8);
		}
		else {
			MultipartEntityBuilder multipartEntityBuilder = MultipartEntityBuilder.create();
			for (NameValuePair nameValuePair : parameters) {
				multipartEntityBuilder.addTextBody(nameValuePair.getName(), nameValuePair.getValue());
			}
			for (String name : multipartParameters.keySet()) {
				multipartEntityBuilder.addPart(name, multipartParameters.get(name));
			}
			httpEntity = multipartEntityBuilder.build();
		}
		httpPost.setEntity(httpEntity);
		return httpPost;

	}

	public HttpEntity getResponse() {
		return httpEntity;
	}

	public String getStringResponse() {
		return httpEntityString;
	}

	public byte[] getByteArrayResponse() {
		return httpEntityFile;
	}

	public StatusLine getStatusLine() {
		return statusLine;
	}

	@Override
	public SfHttpRequest clone() throws CloneNotSupportedException {
		return (SfHttpRequest)super.clone();
	}
	
	private String getDomain(String url) {
		try {
			return new URI(url).getHost();
		} catch (URISyntaxException e) {
			e.printStackTrace();
			return "";
		}
	}
	
	public class TimeoutException extends RuntimeException {
		
		private static final long serialVersionUID = -7871774805119891243L;

		public TimeoutException(String message) {
			super(message);
		}
	}
	
	private static final int EOF = -1;
	
	private static final int DEFAULT_BUFFER_SIZE = 1024 * 4;
	
	private void copyBytes(InputStream input, OutputStream output) {
		try {
			int n;
			byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
		        while (EOF != (n = input.read(buffer))) {
		            output.write(buffer, 0, n);
		        }
		}
		catch (IOException e) {
			log.info("Problem copying input stream", e);
		}
	}
}
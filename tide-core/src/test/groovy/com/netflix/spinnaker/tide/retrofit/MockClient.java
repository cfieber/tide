package com.netflix.spinnaker.tide.retrofit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import retrofit.client.Client;
import retrofit.client.Header;
import retrofit.client.Request;
import retrofit.client.Response;
import retrofit.mime.TypedInput;
import retrofit.mime.TypedString;

@Component("retrofitClient")
public class MockClient implements Client {

  private final Map<Predicate<Request>, Function<Request, Response>> handlers = new LinkedHashMap<>();

  public RequestBuilder onRequest() {
    return new RequestBuilderImpl();
  }

  public RequestBuilder onGet() {
    return onRequest().withMethod("GET");
  }

  public RequestBuilder onPost() {
    return onRequest().withMethod("POST");
  }

  @Override public Response execute(Request request) {
    for (Map.Entry<Predicate<Request>, Function<Request, Response>> handler : handlers.entrySet()) {
      if (handler.getKey().test(request)) return handler.getValue().apply(request);
    }
    return notFound().apply(request);
  }

  public static ResponseBuilder response() {
    return new ResponseBuilder();
  }

  public static ResponseBuilder ok() {
    return response().withStatus(200);
  }

  public static ResponseBuilder notFound() {
    return response().withStatus(404);
  }

  interface RequestBuilder {
    MockClient respondWith(Function<Request, Response> response);

    RequestBuilder withPath(String path);

    RequestBuilder withMethod(String method);

    RequestBuilder withQueryStringParameter(String key, String value);

    RequestBuilder withBody(String text);
  }

  private class RequestBuilderImpl implements RequestBuilder, Supplier<Predicate<Request>> {

    private List<Predicate<Request>> matchers = new ArrayList<>();

    @Override public Predicate<Request> get() {
      return request -> matchers.stream().allMatch((predicate) -> predicate.test(request));
    }

    @Override public MockClient respondWith(Function<Request, Response> response) {
      MockClient.this.handlers.put(get(), response);
      return MockClient.this;
    }

    @Override public RequestBuilder withPath(String path) {
      matchers.add((request) -> requestURL(request).getPath().equals(path));
      return this;
    }

    @Override public RequestBuilder withMethod(String method) {
      matchers.add((request) -> request.getMethod().equals(method));
      return this;
    }

    @Override public RequestBuilder withQueryStringParameter(String key, String value) {
      matchers.add((it) -> {
          Map<String, Collection<String>> query = query(it);
          return query.containsKey(key) && query.get(key).contains(urlEncode(value));
        }
      );
      return this;
    }

    @Override public RequestBuilder withBody(String text) {
      matchers.add((it) -> {
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          try {
            it.getBody().writeTo(out);
            return out.toString().equals(text);
          } catch (IOException e) {
            throw new IllegalStateException("Unreadable request body");
          }
        }
      );
      return this;
    }
  }

  private static class ResponseBuilder implements Function<Request, Response> {

    private int status = 0;
    private List<Header> headers = new ArrayList<>();
    private TypedInput body = new TypedString("");

    private ResponseBuilder() {
    }

    @Override public Response apply(Request request) {
      return new Response(request.getUrl(), status, "", headers, body);
    }

    private ResponseBuilder withStatus(int status) {
      this.status = status;
      return this;
    }

    public ResponseBuilder withHeader(Header header) {
      headers.add(header);
      return this;
    }

    public ResponseBuilder withHeader(String name, String value) {
      return withHeader(new Header(name, value));
    }

    public ResponseBuilder withBody(String body) {
      this.body = new TypedString(body);
      return this;
    }
  }

  private static URL requestURL(Request request) {
    try {
      return new URL(request.getUrl());
    } catch (MalformedURLException e) {
      throw new IllegalStateException("Actual request contains invalid URL");
    }
  }

  private static String urlEncode(String s) {
    try {
      return URLEncoder.encode(s, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException("Invalid encoding", e);
    }
  }

  private static Map<String, Collection<String>> query(Request request) {
    String[] entries = requestURL(request).getQuery().split("&");
    Map<String, Collection<String>> map = new HashMap<>();
    for (String entry : entries) {
      String[] kv = entry.split("=", 2);
      if (!map.containsKey(kv[0])) {
        map.put(kv[0], new ArrayList<>());
      }
      map.get(kv[0]).add(kv[1]);
    }
    return map;
  }
}

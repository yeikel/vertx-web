package io.vertx.ext.web.client.impl;

import java.net.URI;
import java.util.List;

import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.client.spi.CookieStore;

/**
 * A stateless interceptor for session management that operates on the {@code HttpContext}
 */
public class SessionAwareInterceptor implements Handler<HttpContext<?>> {

  private final WebClientSessionAware parentClient;

  public SessionAwareInterceptor(WebClientSessionAware clientSessionAware) {
    this.parentClient = clientSessionAware;
  }

  @Override
  public void handle(HttpContext<?> context) {
    switch(context.phase()) {
      case CREATE_REQUEST:
        createRequest(context);
        break;
      case FOLLOW_REDIRECT:
        processRedirectCookies(context);
        break;
      case DISPATCH_RESPONSE:
        processResponse(context);
        break;
      default:
        break;
    }

    context.next();
  }

  private void createRequest(HttpContext<?> context) {

    RequestOptions request = context.requestOptions();

    RequestOptions requestOptions = context.requestOptions();
    MultiMap headers = requestOptions.getHeaders();
    if (headers == null) {
      headers = HttpHeaders.headers();
      requestOptions.setHeaders(headers);
    }
    headers.addAll(parentClient.headers());

    String domain = context.request().virtualHost();
    if (domain == null) {
      domain = requestOptions.getHost();
    }

    Iterable<Cookie> cookies = parentClient.cookieStore().get(request.isSsl(), domain, request.getURI());
    String encodedCookies = ClientCookieEncoder.STRICT.encode(cookies);
    if (encodedCookies != null) {
      headers.add(HttpHeaders.COOKIE, encodedCookies);
    }
  }

  private void processRedirectCookies(HttpContext<?> context) {
    this.processRedirectResponse(context);
    this.prepareRedirectRequest(context);
  }

  private void processRedirectResponse(HttpContext<?> context) {
    // Now the context contains the redirect request in clientRequest() and the original request in request()
    List<String> cookieHeaders = context.clientResponse().cookies();
    if (cookieHeaders == null) {
      return;
    }

    RequestOptions originalRequest = context.requestOptions();
    CookieStore cookieStore = parentClient.cookieStore();
    String domain = URI.create(context.clientResponse().request().absoluteURI()).getHost();
    if (domain.equals(originalRequest.getHost()) && context.request().virtualHost() != null) {
      domain = context.request().virtualHost();
    }
    final String finalDomain = domain;
    cookieHeaders.forEach(header -> {
      Cookie cookie = ClientCookieDecoder.STRICT.decode(header);
      if (cookie != null) {
        if (cookie.domain() == null) {
          // Set the domain if missing, because we need to send cookies
          // only to the domains we received them from.
          cookie.setDomain(finalDomain);
        }
        cookieStore.put(cookie);
      }
    });
  }

  private void prepareRedirectRequest(HttpContext<?> context) {
    // Now the context contains the redirect request in clientRequest() and the original request in request()
    RequestOptions redirectRequest = context.requestOptions();

    RequestOptions originalRequest = context.requestOptions();
    String redirectHost = redirectRequest.getHost();
    String domain;
    if (redirectHost.equals(originalRequest.getHost()) && context.request().virtualHost() != null) {
      domain = context.request().virtualHost();
    } else {
      domain = redirectHost;
    }

    String path = parsePath(redirectRequest.getURI());
    Iterable<Cookie> cookies = parentClient.cookieStore().get(originalRequest.isSsl(), domain, path);
    String encodedCookies = ClientCookieEncoder.STRICT.encode(cookies);
    if (encodedCookies != null) {
      redirectRequest.putHeader(HttpHeaders.COOKIE, encodedCookies);
    }
  }

  private static String parsePath(String uri) {
    if (uri.length() == 0) {
      return "";
    }
    int i;
    if (uri.charAt(0) == '/') {
      i = 0;
    } else {
      i = uri.indexOf("://");
      if (i == -1) {
        i = 0;
      } else {
        i = uri.indexOf('/', i + 3);
        if (i == -1) {
          // contains no /
          return "/";
        }
      }
    }

    int queryStart = uri.indexOf('?', i);
    if (queryStart == -1) {
      queryStart = uri.length();
    }
    return uri.substring(i, queryStart);
  }

  private void processResponse(HttpContext<?> context) {
    List<String> cookieHeaders = context.response().cookies();
    if (cookieHeaders == null) {
      return;
    }

    RequestOptions request = context.requestOptions();
    CookieStore cookieStore = parentClient.cookieStore();
    cookieHeaders.forEach(header -> {
      Cookie cookie = ClientCookieDecoder.STRICT.decode(header);
      if (cookie != null) {
        if (cookie.domain() == null) {
          // Set the domain if missing, because we need to send cookies
          // only to the domains we received them from.
          cookie.setDomain(context.request().virtualHost() != null ? context.request().virtualHost() : request.getHost());
        }
        cookieStore.put(cookie);
      }
    });
  }
}

package com.quarantyne.proxy.verticles;

import com.google.common.base.Joiner;
import com.quarantyne.core.classifiers.CompositeClassifier;
import com.quarantyne.core.classifiers.Label;
import com.quarantyne.core.lib.HttpRequest;
import com.quarantyne.core.lib.HttpRequestMethod;
import com.quarantyne.core.util.CaseInsensitiveStringKV;
import com.quarantyne.proxy.QuarantyneHeaders;
import com.quarantyne.proxy.ServerConfig;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ProxyVerticle extends AbstractVerticle {

  private final ServerConfig serverConfig;
  private final CompositeClassifier quarantyneClassifier;
  private HttpClient httpClient;

  public ProxyVerticle(ServerConfig serverConfig, CompositeClassifier quarantyneClassifier) {
    this.serverConfig = serverConfig;
    this.quarantyneClassifier = quarantyneClassifier;
  }

  @Override
  public void start(Future<Void> startFuture) {
    // proxy server (this server)
    HttpServerOptions httpServerOptions = new HttpServerOptions();
    httpServerOptions.setHost(serverConfig.getProxyHost());
    httpServerOptions.setUsePooledBuffers(true);
    HttpServer httpServer = vertx.createHttpServer(httpServerOptions);

    // http client to remote
    HttpClientOptions httpClientOptions = new HttpClientOptions();
    httpClientOptions.setKeepAlive(true);
    httpClientOptions.setLogActivity(false);

    if (serverConfig.getSsl() | serverConfig.getRemotePort() == 443) {
      httpClientOptions.setSsl(true);
    }
    httpClientOptions.setDefaultHost(serverConfig.getRemoteHost());
    httpClientOptions.setDefaultPort(serverConfig.getRemotePort());

    this.httpClient = vertx.createHttpClient(httpClientOptions);

    httpServer.requestHandler(frontReq -> {
      if (frontReq.method().equals(HttpMethod.POST) || frontReq.method().equals(HttpMethod.PUT)) {
        frontReq.bodyHandler(reqBody -> {
          proxiedRequestHandler(frontReq, reqBody);
        });
      } else {
        proxiedRequestHandler(frontReq, null);
      }
    });

    httpServer.exceptionHandler(ex -> {
      log.error("HTTP server error", ex);
    });

    httpServer.listen(serverConfig.getProxyPort(), serverConfig.getProxyHost(), h -> {
      if (h.failed()) {
        log.error("proxy failed to start", h.cause());
        startFuture.fail(h.cause());
      }
    });
  }

  private void proxiedRequestHandler(HttpServerRequest frontReq, Buffer frontReqBody) {
    HttpServerResponse frontRep = frontReq.response();
    HttpClientRequest backReq = httpClient.request(
        frontReq.method(),
        frontReq.uri()
    );

    backReq.headers().setAll(frontReq.headers());
    backReq.headers().set(HttpHeaders.HOST, serverConfig.getRemoteHost());
    // inject quarantyne headers, if any
    backReq.headers().addAll(quarantyneCheck(frontReq, frontReqBody));
    // --------------------------------
    backReq.handler(backRep -> {
      Buffer body = Buffer.buffer();
      backRep.handler(body::appendBuffer);
      backRep.endHandler(h -> {
        frontRep.setStatusCode(backRep.statusCode());
        frontRep.headers().setAll(backRep.headers());
        frontRep.end(body);
      });
    });
    backReq.exceptionHandler(ex -> {
      log.error("error while querying downstream service", ex);
      frontRep.setStatusCode(500);
      frontRep.end("Internal Server Error. This request cannot be satisfied.");
    });
    if (frontReqBody != null) {
      backReq.end(frontReqBody);
    } else {
      backReq.end();
    }
  }

  private Joiner joiner = Joiner.on(",");

  // returns quarantyne headers
  private MultiMap quarantyneCheck(HttpServerRequest req) {
    return quarantyneCheck(req, Buffer.buffer());
  }

  private MultiMap quarantyneCheck(HttpServerRequest req, Buffer body) {
    HttpRequest httpRequest = new HttpRequest(
        HttpRequestMethod.valueOf(req.method().toString().toUpperCase()),
        new CaseInsensitiveStringKV(req.headers().entries()),
        req.remoteAddress().host(),
        req.path()
    );
    MultiMap quarantyneHeaders = MultiMap.caseInsensitiveMultiMap();
    Set<Label> quarantyneLabels = quarantyneClassifier.classify(httpRequest);
    if (!quarantyneLabels.isEmpty()) {
      quarantyneHeaders.add(QuarantyneHeaders.LABELS, joiner.join(quarantyneLabels));
      quarantyneHeaders.add(QuarantyneHeaders.TRACE_ID, UUID.randomUUID().toString());
    }
    return quarantyneHeaders;
  }
}
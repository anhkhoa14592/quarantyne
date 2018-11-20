package com.quarantyne.classifiers.impl;


import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.quarantyne.classifiers.HttpRequestClassifier;
import com.quarantyne.classifiers.Label;
import com.quarantyne.lib.HttpRequest;
import com.quarantyne.lib.HttpRequestBody;
import com.quarantyne.util.ExponentialBackOff;
import java.time.Duration;
import java.util.Set;
import javax.annotation.Nullable;

public class FastAgentClassifier implements HttpRequestClassifier {

  private final Cache<HttpRequest, ExponentialBackOff> penaltyBoxCache;
  private final Cache<HttpRequest, Boolean> visitCountCache;

  public FastAgentClassifier() {
    this.visitCountCache = Caffeine
        .newBuilder()
        .expireAfterWrite(Duration.ofMillis(900))
        .build();

    this.penaltyBoxCache = Caffeine
        .newBuilder()
        .expireAfterWrite(Duration.ofMinutes(20))
        .build();
  }

  @Override
  public Set<Label> classify(final HttpRequest httpRequest, @Nullable final HttpRequestBody body) {
    ExponentialBackOff backoff = penaltyBoxCache.getIfPresent(httpRequest);
    if (backoff != null) {
      if (backoff.isBackedOff()) {
        backoff.touch();
        return Label.FAST_BROWSER;
      } else {
        penaltyBoxCache.invalidate(backoff);
        return EMPTY_LABELS;
      }
    }
    if (visitCountCache.getIfPresent(httpRequest) != null) {
        penaltyBoxCache.put(httpRequest, ExponentialBackOff.DEFAULT);
    }
    visitCountCache.put(httpRequest, true);
    return EMPTY_LABELS;
  }
}

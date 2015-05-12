/*
 * Copyright 2014 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.apex.handler.sockjs.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.apex.handler.sockjs.BridgeEvent;
import io.vertx.ext.apex.handler.sockjs.SockJSSocket;
/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
class BridgeEventImpl implements BridgeEvent {

  private final Type type;
  private final JsonObject rawMessage;
  private final SockJSSocket socket;
  private Future<Boolean> future;

  public BridgeEventImpl(Type type, JsonObject rawMessage, SockJSSocket socket) {
    this.type = type;
    this.rawMessage = rawMessage;
    this.socket = socket;
  }

  @Override
  public Type type() {
    return type;
  }

  @Override
  public JsonObject rawMessage() {
    return rawMessage;
  }

  @Override
  public SockJSSocket socket() {
    return socket;
  }

  public void setFuture(Future<Boolean> future) {
    this.future = future;
  }

  @Override
  public boolean isComplete() {
    return future.isComplete();
  }

  @Override
  public void setHandler(Handler<AsyncResult<Boolean>> handler) {
    future.setHandler(handler);
  }

  @Override
  public void complete(Boolean result) {
    future.complete(result);
  }

  @Override
  public void complete() {
    future.complete();
  }

  @Override
  public void fail(Throwable throwable) {
    future.fail(throwable);
  }

  @Override
  public void fail(String failureMessage) {
    future.fail(failureMessage);
  }

  @Override
  public Boolean result() {
    return future.result();
  }

  @Override
  public Throwable cause() {
    return future.cause();
  }

  @Override
  public boolean succeeded() {
    return future.succeeded();
  }

  @Override
  public boolean failed() {
    return future.failed();
  }
}

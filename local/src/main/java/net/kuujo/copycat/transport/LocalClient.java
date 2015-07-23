/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.transport;

import net.kuujo.alleycat.Alleycat;
import net.kuujo.copycat.util.concurrent.Context;
import net.kuujo.copycat.util.concurrent.Futures;
import net.kuujo.copycat.util.concurrent.SingleThreadContext;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local client.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class LocalClient implements Client {
  private final UUID id;
  private final LocalServerRegistry registry;
  private final Context context;
  private final Set<LocalConnection> connections = Collections.newSetFromMap(new ConcurrentHashMap<>());

  public LocalClient(UUID id, LocalServerRegistry registry, Alleycat serializer) {
    this.id = id;
    this.registry = registry;
    this.context = new SingleThreadContext("test-" + id.toString(), serializer.clone());
  }

  @Override
  public UUID id() {
    return id;
  }

  /**
   * Returns the current execution context.
   */
  private Context getContext() {
    Context context = Context.currentContext();
    if (context == null) {
      throw new IllegalStateException("not on a Copycat thread");
    }
    return context;
  }

  @Override
  public CompletableFuture<Connection> connect(InetSocketAddress address) {
    Context context = getContext();
    LocalServer server = registry.get(address);
    if (server == null) {
      return Futures.exceptionalFutureAsync(new TransportException("failed to connect"), context);
    }

    LocalConnection connection = new LocalConnection(id, this.context, connections);
    server.connect(connection);
    connections.add(connection);
    return Futures.completedFutureAsync(connection, context);
  }

  @Override
  public CompletableFuture<Void> close() {
    CompletableFuture<Void> future = new CompletableFuture<>();

    Context context = getContext();
    CompletableFuture[] futures = new CompletableFuture[connections.size()];
    int i = 0;
    for (LocalConnection connection : connections) {
      futures[i++] = connection.close();
    }
    CompletableFuture.allOf(futures).thenRunAsync(() -> future.complete(null), context);
    return future;
  }

}

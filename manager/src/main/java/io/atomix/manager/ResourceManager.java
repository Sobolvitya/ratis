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
package io.atomix.manager;

import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.transport.Address;
import io.atomix.catalyst.transport.Transport;
import io.atomix.catalyst.util.Assert;
import io.atomix.catalyst.util.Managed;
import io.atomix.catalyst.util.concurrent.ThreadContext;
import io.atomix.copycat.client.*;
import io.atomix.manager.state.GetResourceKeys;
import io.atomix.manager.state.ResourceExists;
import io.atomix.resource.*;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base type for creating and managing distributed {@link Resource resources} in a Atomix cluster.
 * <p>
 * Resources are user provided stateful objects backed by a distributed state machine. This class facilitates the
 * creation and management of {@link Resource} objects via a filesystem like interface. There is a
 * one-to-one relationship between keys and resources, so each key can be associated with one and only one resource.
 * <p>
 * To create a resource, pass the resource {@link java.lang.Class} to the {@link ResourceManager#create(String, ResourceType)} method.
 * When a resource is created, the {@link io.atomix.copycat.server.StateMachine} associated with the resource will be created on each Raft server
 * and future operations submitted for that resource will be applied to the state machine. Internally, resource state
 * machines are multiplexed across a shared Raft log.
 * <p>
 * {@link Resource} implementations serve as a user-friendly interface through which to submit
 * {@link io.atomix.copycat.client.Command commands} and {@link io.atomix.copycat.client.Query queries} to the underlying
 * {@link CopycatClient} client.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public abstract class ResourceManager implements Managed<ResourceManager> {
  final CopycatClient client;
  final InstanceManager manager;
  private final ResourceRegistry registry;
  private final Map<Class<? extends Resource<?, ?>>, ResourceType> types = new ConcurrentHashMap<>();

  /**
   * @throws NullPointerException if {@code client} is null
   */
  protected ResourceManager(CopycatClient client, ResourceRegistry registry) {
    this.client = Assert.notNull(client, "client");
    this.registry = Assert.notNull(registry, "registry");
    this.manager = new InstanceManager(client);
  }

  /**
   * Returns the Atomix thread context.
   * <p>
   * This context is representative of the thread on which asynchronous callbacks will be executed for this
   * Atomix instance. Atomix guarantees that all {@link CompletableFuture}s supplied by this instance will
   * be executed via the returned context. Users can use the context to access the thread-local
   * {@link Serializer}.
   *
   * @return The Atomix thread context.
   */
  public ThreadContext context() {
    return client.context();
  }

  /**
   * Returns the resource type for the given resource class.
   *
   * @param type The resource class.
   * @return The resource type for the given resource class.
   */
  public final ResourceType type(Class<? extends Resource<?, ?>> type) {
    return types.computeIfAbsent(type, t -> {
      ResourceType resourceType = new ResourceType(type);
      if (registry.lookup(resourceType.id()) == null)
        throw new IllegalArgumentException("unregistered resource type");
      return resourceType;
    });
  }

  /**
   * Checks whether a resource exists with the given key.
   * <p>
   * If no resource with the given {@code key} exists in the cluster, the returned {@link CompletableFuture} will
   * be completed {@code false}. Note, however, that users should not significantly rely upon the existence or
   * non-existence of a resource due to race conditions. While a resource may not exist when the returned future is
   * completed, it may be created by another node shortly thereafter.
   * <p>
   * This method returns a {@link CompletableFuture} which can be used to block until the operation completes
   * or to be notified in a separate thread once the operation completes. To block until the operation completes,
   * use the {@link CompletableFuture#get()} method:
   * <pre>
   *   {@code
   *   if (!atomix.exists("lock").get()) {
   *     DistributedLock lock = atomix.create("lock", DistributedLock.class).get();
   *   }
   *   }
   * </pre>
   * Alternatively, to execute the operation asynchronous and be notified once the result is received in a different
   * thread, use one of the many completable future callbacks:
   * <pre>
   *   {@code
   *   atomix.exists("lock").thenAccept(exists -> {
   *     if (!exists) {
   *       atomix.<DistributedLock>create("lock", DistributedLock.class).thenAccept(lock -> {
   *         ...
   *       });
   *     }
   *   });
   *   }
   * </pre>
   *
   * @param key The key to check.
   * @return A completable future indicating whether the given key exists.
   * @throws NullPointerException if {@code key} is null
   */
  public CompletableFuture<Boolean> exists(String key) {
    return client.submit(new ResourceExists(key));
  }

  /**
   * Returns keys of all existing resources.
   *
   * <p>
   * This method returns a {@link CompletableFuture} which can be used to block until the operation completes
   * or to be notified in a separate thread once the operation completes. To block until the operation completes,
   * use the {@link CompletableFuture#get()} method:
   * <pre>
   *   {@code
   *   Collection<String> resourceKeys = atomix.keys().get();
   *   }
   * </pre>
   * Alternatively, to execute the operation asynchronous and be notified once the result is received in a different
   * thread, use one of the many completable future callbacks:
   * <pre>
   *   {@code
   *   atomix.<Collection<String>>keys().thenAccept(resourceKeys -> {
   *     ...
   *   });
   *   }
   * </pre>
   *
   * @return A completable future to be completed with the keys of all existing resources.
   */
  public CompletableFuture<Set<String>> keys() {
    return client.submit(new GetResourceKeys());
  }

  /**
   * Returns the keys of existing resources belonging to a resource type.
   *
   * <p>
   * This method returns a {@link CompletableFuture} which can be used to block until the operation completes
   * or to be notified in a separate thread once the operation completes. To block until the operation completes,
   * use the {@link CompletableFuture#get()} method:
   * <pre>
   *   {@code
   *   Set<String> resourceKeys = atomix.keys(DistributedLock.class).get();
   *   }
   * </pre>
   * Alternatively, to execute the operation asynchronous and be notified once the result is received in a different
   * thread, use one of the many completable future callbacks:
   * <pre>
   *   {@code
   *   atomix.<Set<String>>keys().thenAccept(resourceKeys -> {
   *     ...
   *   });
   *   }
   * </pre>
   *
   * @param type The resource type by which to filter resources.
   * @param <T> The resource type.
   * @return A completable future to be completed with the set of resource keys.
   */
  @SuppressWarnings("unchecked")
  public <T extends Resource> CompletableFuture<Set<String>> keys(Class<? super T> type) {
    return keys(type((Class<? extends Resource<?, ?>>) type));
  }

  /**
   * Returns the keys of existing resources belonging to a resource type.
   *
   * <p>
   * This method returns a {@link CompletableFuture} which can be used to block until the operation completes
   * or to be notified in a separate thread once the operation completes. To block until the operation completes,
   * use the {@link CompletableFuture#get()} method:
   * <pre>
   *   {@code
   *   Set<String> resourceKeys = atomix.keys(DistributedLock.class).get();
   *   }
   * </pre>
   * Alternatively, to execute the operation asynchronous and be notified once the result is received in a different
   * thread, use one of the many completable future callbacks:
   * <pre>
   *   {@code
   *   atomix.<Set<String>>keys().thenAccept(resourceKeys -> {
   *     ...
   *   });
   *   }
   * </pre>
   *
   * @param type The resource type by which to filter resources.
   * @return A completable future to be completed with the set of resource keys.
   */
  public CompletableFuture<Set<String>> keys(ResourceType type) {
    return client.submit(new GetResourceKeys(Assert.notNull(type, "type").id()));
  }

  /**
   * Gets or creates the given resource and acquires a singleton reference to it.
   * <p>
   * If a resource at the given key already exists, the resource will be validated to verify that its type
   * matches the given type. If no resource yet exists, a new resource will be created in the cluster. Once
   * the session for the resource has been opened, a resource instance will be returned.
   * <p>
   * The returned {@link Resource} instance will be a singleton reference to an global instance for this node.
   * That is, multiple calls to this method for the same resource will result in the same {@link Resource}
   * instance being returned.
   * <p>
   * This method returns a {@link CompletableFuture} which can be used to block until the operation completes
   * or to be notified in a separate thread once the operation completes. To block until the operation completes,
   * use the {@link CompletableFuture#get()} method:
   * <pre>
   *   {@code
   *   DistributedLock lock = atomix.get("lock", DistributedLock.class).get();
   *   }
   * </pre>
   * Alternatively, to execute the operation asynchronous and be notified once the result is received in a different
   * thread, use one of the many completable future callbacks:
   * <pre>
   *   {@code
   *   atomix.<DistributedLock>get("lock", DistributedLock.class).thenAccept(lock -> {
   *     ...
   *   });
   *   }
   * </pre>
   *
   * @param key The key at which to get the resource.
   * @param type The expected resource type.
   * @param <T> The resource type.
   * @return A completable future to be completed once the resource has been loaded.
   * @throws NullPointerException if {@code key} or {@code type} are null
   */
  @SuppressWarnings("unchecked")
  public <T extends Resource> CompletableFuture<T> get(String key, Class<? super T> type) {
    return get(key, type((Class<? extends Resource<?, ?>>) type));
  }

  /**
   * Gets or creates the given resource and acquires a singleton reference to it.
   * <p>
   * If a resource at the given key already exists, the resource will be validated to verify that its type
   * matches the given type. If no resource yet exists, a new resource will be created in the cluster. Once
   * the session for the resource has been opened, a resource instance will be returned.
   * <p>
   * The returned {@link Resource} instance will be a singleton reference to an global instance for this node.
   * That is, multiple calls to this method for the same resource will result in the same {@link Resource}
   * instance being returned.
   * <p>
   * This method returns a {@link CompletableFuture} which can be used to block until the operation completes
   * or to be notified in a separate thread once the operation completes. To block until the operation completes,
   * use the {@link CompletableFuture#get()} method:
   * <pre>
   *   {@code
   *   DistributedLock lock = atomix.get("lock", DistributedLock.class).get();
   *   }
   * </pre>
   * Alternatively, to execute the operation asynchronous and be notified once the result is received in a different
   * thread, use one of the many completable future callbacks:
   * <pre>
   *   {@code
   *   atomix.<DistributedLock>get("lock", DistributedLock.class).thenAccept(lock -> {
   *     ...
   *   });
   *   }
   * </pre>
   *
   * @param key The key at which to get the resource.
   * @param type The expected resource type.
   * @param <T> The resource type.
   * @return A completable future to be completed once the resource has been loaded.
   * @throws NullPointerException if {@code key} or {@code type} are null
   */
  @SuppressWarnings("unchecked")
  public <T extends Resource<T, U>, U extends Resource.Options> CompletableFuture<T> get(String key, Class<? super T> type, U options) {
    return this.<T, U>get(key, type((Class<? extends Resource<?, ?>>) type), options);
  }

  /**
   * Gets or creates the given resource and acquires a singleton reference to it.
   * <p>
   * If a resource at the given key already exists, the resource will be validated to verify that its type
   * matches the given type. If no resource yet exists, a new resource will be created in the cluster. Once
   * the session for the resource has been opened, a resource instance will be returned.
   * <p>
   * The returned {@link Resource} instance will be a singleton reference to an global instance for this node.
   * That is, multiple calls to this method for the same resource will result in the same {@link Resource}
   * instance being returned.
   * <p>
   * This method returns a {@link CompletableFuture} which can be used to block until the operation completes
   * or to be notified in a separate thread once the operation completes. To block until the operation completes,
   * use the {@link CompletableFuture#get()} method:
   * <pre>
   *   {@code
   *   DistributedLock lock = atomix.get("lock", DistributedLock.class).get();
   *   }
   * </pre>
   * Alternatively, to execute the operation asynchronous and be notified once the result is received in a different
   * thread, use one of the many completable future callbacks:
   * <pre>
   *   {@code
   *   atomix.<DistributedLock>get("lock", DistributedLock.class).thenAccept(lock -> {
   *     ...
   *   });
   *   }
   * </pre>
   *
   * @param key The key at which to get the resource.
   * @param type The expected resource type.
   * @param <T> The resource type.
   * @return A completable future to be completed once the resource has been loaded.
   * @throws NullPointerException if {@code key} or {@code type} are null
   */
  @SuppressWarnings("unchecked")
  public <T extends Resource> CompletableFuture<T> get(String key, ResourceType type) {
    return get(key, type, null);
  }

  /**
   * Gets or creates the given resource and acquires a singleton reference to it.
   * <p>
   * If a resource at the given key already exists, the resource will be validated to verify that its type
   * matches the given type. If no resource yet exists, a new resource will be created in the cluster. Once
   * the session for the resource has been opened, a resource instance will be returned.
   * <p>
   * The returned {@link Resource} instance will be a singleton reference to an global instance for this node.
   * That is, multiple calls to this method for the same resource will result in the same {@link Resource}
   * instance being returned.
   * <p>
   * This method returns a {@link CompletableFuture} which can be used to block until the operation completes
   * or to be notified in a separate thread once the operation completes. To block until the operation completes,
   * use the {@link CompletableFuture#get()} method:
   * <pre>
   *   {@code
   *   DistributedLock lock = atomix.get("lock", DistributedLock.class).get();
   *   }
   * </pre>
   * Alternatively, to execute the operation asynchronous and be notified once the result is received in a different
   * thread, use one of the many completable future callbacks:
   * <pre>
   *   {@code
   *   atomix.<DistributedLock>get("lock", DistributedLock.class).thenAccept(lock -> {
   *     ...
   *   });
   *   }
   * </pre>
   *
   * @param key The key at which to get the resource.
   * @param type The expected resource type.
   * @param <T> The resource type.
   * @return A completable future to be completed once the resource has been loaded.
   * @throws NullPointerException if {@code key} or {@code type} are null
   */
  @SuppressWarnings("unchecked")
  public <T extends Resource<T, U>, U extends Resource.Options> CompletableFuture<T> get(String key, ResourceType type, U options) {
    return manager.get(key, type, options);
  }

  /**
   * Creates a new instance for the given resource.
   * <p>
   * If a resource at the given key already exists, the resource will be validated to verify that its type
   * matches the given type. If no resource yet exists, a new resource will be created in the cluster. Once
   * the session for the resource has been opened, a new resource instance will be returned.
   * <p>
   * The returned {@link Resource} instance will have a unique logical connection to the resource state. This
   * means that operations and events submitted or received by this instance related to this instance only,
   * even if multiple instances of the resource are open on this node. For instance, a lock resource created
   * via this method will behave as a unique reference to the distributed state. Locking a lock acquired via this
   * method will lock <em>only</em> that lock instance and not other instance of the lock on this node.
   * <p>
   * To acquire a singleton reference to a resource that is global to this node, use the {@link #get(String, ResourceType)}
   * method.
   * <p>
   * This method returns a {@link CompletableFuture} which can be used to block until the operation completes
   * or to be notified in a separate thread once the operation completes. To block until the operation completes,
   * use the {@link CompletableFuture#get()} method:
   * <pre>
   *   {@code
   *   DistributedLock lock = atomix.create("lock", DistributedLock.class).get();
   *   }
   * </pre>
   * Alternatively, to execute the operation asynchronous and be notified once the result is received in a different
   * thread, use one of the many completable future callbacks:
   * <pre>
   *   {@code
   *   atomix.<DistributedLock>create("lock", DistributedLock.class).thenAccept(lock -> {
   *     ...
   *   });
   *   }
   * </pre>
   *
   * @param key The key at which to create the resource.
   * @param type The resource type to create.
   * @param <T> The resource type.
   * @return A completable future to be completed once the resource has been created.
   * @throws NullPointerException if {@code key} or {@code type} are null
   * @throws ResourceException if the resource could not be instantiated
   */
  @SuppressWarnings("unchecked")
  public <T extends Resource> CompletableFuture<T> create(String key, Class<? super T> type) {
    return create(key, type((Class<? extends Resource<?, ?>>) type), null);
  }

  /**
   * Creates a new instance for the given resource.
   * <p>
   * If a resource at the given key already exists, the resource will be validated to verify that its type
   * matches the given type. If no resource yet exists, a new resource will be created in the cluster. Once
   * the session for the resource has been opened, a new resource instance will be returned.
   * <p>
   * The returned {@link Resource} instance will have a unique logical connection to the resource state. This
   * means that operations and events submitted or received by this instance related to this instance only,
   * even if multiple instances of the resource are open on this node. For instance, a lock resource created
   * via this method will behave as a unique reference to the distributed state. Locking a lock acquired via this
   * method will lock <em>only</em> that lock instance and not other instance of the lock on this node.
   * <p>
   * To acquire a singleton reference to a resource that is global to this node, use the {@link #get(String, ResourceType)}
   * method.
   * <p>
   * This method returns a {@link CompletableFuture} which can be used to block until the operation completes
   * or to be notified in a separate thread once the operation completes. To block until the operation completes,
   * use the {@link CompletableFuture#get()} method:
   * <pre>
   *   {@code
   *   DistributedLock lock = atomix.create("lock", DistributedLock.class).get();
   *   }
   * </pre>
   * Alternatively, to execute the operation asynchronous and be notified once the result is received in a different
   * thread, use one of the many completable future callbacks:
   * <pre>
   *   {@code
   *   atomix.<DistributedLock>create("lock", DistributedLock.class).thenAccept(lock -> {
   *     ...
   *   });
   *   }
   * </pre>
   *
   * @param key The key at which to create the resource.
   * @param type The resource type to create.
   * @param <T> The resource type.
   * @return A completable future to be completed once the resource has been created.
   * @throws NullPointerException if {@code key} or {@code type} are null
   * @throws ResourceException if the resource could not be instantiated
   */
  @SuppressWarnings("unchecked")
  public <T extends Resource<T, U>, U extends Resource.Options> CompletableFuture<T> create(String key, Class<? super T> type, U options) {
    return this.<T, U>create(key, type((Class<T>) type), options);
  }

  /**
   * Creates a new instance for the given resource.
   * <p>
   * If a resource at the given key already exists, the resource will be validated to verify that its type
   * matches the given type. If no resource yet exists, a new resource will be created in the cluster. Once
   * the session for the resource has been opened, a new resource instance will be returned.
   * <p>
   * The returned {@link Resource} instance will have a unique logical connection to the resource state. This
   * means that operations and events submitted or received by this instance related to this instance only,
   * even if multiple instances of the resource are open on this node. For instance, a lock resource created
   * via this method will behave as a unique reference to the distributed state. Locking a lock acquired via this
   * method will lock <em>only</em> that lock instance and not other instance of the lock on this node.
   * <p>
   * To acquire a singleton reference to a resource that is global to this node, use the {@link #get(String, ResourceType)}
   * method.
   * <p>
   * This method returns a {@link CompletableFuture} which can be used to block until the operation completes
   * or to be notified in a separate thread once the operation completes. To block until the operation completes,
   * use the {@link CompletableFuture#get()} method:
   * <pre>
   *   {@code
   *   DistributedLock lock = atomix.create("lock", DistributedLock.class).get();
   *   }
   * </pre>
   * Alternatively, to execute the operation asynchronous and be notified once the result is received in a different
   * thread, use one of the many completable future callbacks:
   * <pre>
   *   {@code
   *   atomix.<DistributedLock>create("lock", DistributedLock.class).thenAccept(lock -> {
   *     ...
   *   });
   *   }
   * </pre>
   *
   * @param key The key at which to create the resource.
   * @param type The resource type to create.
   * @param <T> The resource type.
   * @return A completable future to be completed once the resource has been created.
   * @throws NullPointerException if {@code key} or {@code type} are null
   * @throws ResourceException if the resource could not be instantiated
   */
  public <T extends Resource> CompletableFuture<T> create(String key, ResourceType type) {
    return create(key, type, null);
  }

  /**
   * Creates a new instance for the given resource.
   * <p>
   * If a resource at the given key already exists, the resource will be validated to verify that its type
   * matches the given type. If no resource yet exists, a new resource will be created in the cluster. Once
   * the session for the resource has been opened, a new resource instance will be returned.
   * <p>
   * The returned {@link Resource} instance will have a unique logical connection to the resource state. This
   * means that operations and events submitted or received by this instance related to this instance only,
   * even if multiple instances of the resource are open on this node. For instance, a lock resource created
   * via this method will behave as a unique reference to the distributed state. Locking a lock acquired via this
   * method will lock <em>only</em> that lock instance and not other instance of the lock on this node.
   * <p>
   * To acquire a singleton reference to a resource that is global to this node, use the {@link #get(String, ResourceType)}
   * method.
   * <p>
   * This method returns a {@link CompletableFuture} which can be used to block until the operation completes
   * or to be notified in a separate thread once the operation completes. To block until the operation completes,
   * use the {@link CompletableFuture#get()} method:
   * <pre>
   *   {@code
   *   DistributedLock lock = atomix.create("lock", DistributedLock.class).get();
   *   }
   * </pre>
   * Alternatively, to execute the operation asynchronous and be notified once the result is received in a different
   * thread, use one of the many completable future callbacks:
   * <pre>
   *   {@code
   *   atomix.<DistributedLock>create("lock", DistributedLock.class).thenAccept(lock -> {
   *     ...
   *   });
   *   }
   * </pre>
   *
   * @param key The key at which to create the resource.
   * @param type The resource type to create.
   * @param <T> The resource type.
   * @return A completable future to be completed once the resource has been created.
   * @throws NullPointerException if {@code key} or {@code type} are null
   * @throws ResourceException if the resource could not be instantiated
   */
  public <T extends Resource<T, U>, U extends Resource.Options> CompletableFuture<T> create(String key, ResourceType type, U options) {
    return manager.<T, U>create(key, type, options);
  }

  /**
   * Opens the instance.
   *
   * @return A completable future to be completed once the instance is open.
   */
  @Override
  public CompletableFuture<ResourceManager> open() {
    return client.open().thenApply(v -> this);
  }

  /**
   * Returns a boolean value indicating whether the instance is open.
   *
   * @return Indicates whether the instance is open.
   */
  @Override
  public boolean isOpen() {
    return client.isOpen();
  }

  /**
   * Closes the instance.
   *
   * @return A completable future to be completed once the instance is closed.
   */
  @Override
  public CompletableFuture<Void> close() {
    return manager.close().thenCompose(v -> client.close());
  }

  /**
   * Returns a boolean value indicating whether the instance is closed.
   *
   * @return Indicates whether the instance is closed.
   */
  @Override
  public boolean isClosed() {
    return client.isClosed();
  }

  @Override
  public String toString() {
    return String.format("%s[session=%s]", getClass().getSimpleName(), client.session());
  }

  /**
   * Builds an {@link ResourceManager} object.
   */
  public static abstract class Builder extends io.atomix.catalyst.util.Builder<ResourceManager> {
    protected CopycatClient.Builder clientBuilder;
    protected ResourceTypeResolver resourceResolver = new ServiceLoaderResourceResolver();

    protected Builder(Collection<Address> members) {
      clientBuilder = CopycatClient.builder(members)
        .withServerSelectionStrategy(ServerSelectionStrategies.ANY)
        .withConnectionStrategy(ConnectionStrategies.FIBONACCI_BACKOFF)
        .withRecoveryStrategy(RecoveryStrategies.RECOVER)
        .withRetryStrategy(RetryStrategies.FIBONACCI_BACKOFF);
    }

    /**
     * Sets the Atomix transport.
     * <p>
     * The configured transport should be the same transport as all other nodes in the cluster.
     * If no transport is explicitly provided, the instance will default to the {@code NettyTransport}
     * if available on the classpath.
     *
     * @param transport The Atomix transport.
     * @return The Atomix builder.
     * @throws NullPointerException if {@code transport} is {@code null}
     */
    public Builder withTransport(Transport transport) {
      clientBuilder.withTransport(transport);
      return this;
    }

    /**
     * Sets the Atomix serializer.
     * <p>
     * The serializer will be used to serialize and deserialize operations that are sent over the wire.
     *
     * @param serializer The Atomix serializer.
     * @return The Atomix builder.
     */
    public Builder withSerializer(Serializer serializer) {
      clientBuilder.withSerializer(serializer);
      return this;
    }

    /**
     * Sets the Atomix resource type resolver.
     *
     * @param resolver The resource type resolver.
     * @return The Atomix builder.
     */
    public Builder withResourceResolver(ResourceTypeResolver resolver) {
      this.resourceResolver = Assert.notNull(resolver, "resolver");
      return this;
    }
  }

}

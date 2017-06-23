/*
 * Copyright 2017-present Open Networking Laboratory
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
package io.atomix.storage.journal;

import java.util.concurrent.locks.Lock;

/**
 * Log writer.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public interface JournalWriter<E> extends AutoCloseable {

  /**
   * Returns the writer lock.
   *
   * @return The writer lock.
   */
  Lock getLock();

  /**
   * Returns the last written index.
   *
   * @return The last written index.
   */
  long getLastIndex();

  /**
   * Returns the last entry written.
   *
   * @return The last entry written.
   */
  Indexed<E> getLastEntry();

  /**
   * Returns the next index to be written.
   *
   * @return The next index to be written.
   */
  long getNextIndex();

  /**
   * Appends an entry to the journal.
   *
   * @param entry The entry to append.
   * @return The appended indexed entry.
   */
  <T extends E> Indexed<T> appendEntry(T entry);

  /**
   * Appends an indexed entry to the log.
   *
   * @param entry The indexed entry to append.
   */
  void appendEntry(Indexed<E> entry);

  /**
   * Truncates the log to the given index.
   *
   * @param index The index to which to truncate the log.
   */
  void truncate(long index);

  /**
   * Flushes written entries to disk.
   */
  void flush();

  @Override
  void close();
}
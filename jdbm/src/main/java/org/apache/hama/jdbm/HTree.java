/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hama.jdbm;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.IOError;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Persistent HashMap implementation for DB. Implemented as an H*Tree structure.
 */
@SuppressWarnings("rawtypes")
public final class HTree<K, V> extends AbstractMap<K, V> {

  final Serializer SERIALIZER = new Serializer<Object>() {

    @SuppressWarnings("unchecked")
    @Override
    public Object deserialize(DataInput ds2) throws IOException {
      DataInputOutput ds = (DataInputOutput) ds2;
      try {
        int i = ds.readUnsignedByte();
        if (i == SerializationHeader.HTREE_BUCKET) { // is HashBucket?
          HTreeBucket ret = new HTreeBucket(HTree.this);
          if (loadValues)
            ret.readExternal(ds);

          if (loadValues && ds.available() != 0)
            throw new InternalError("bytes left: " + ds.available());
          return ret;
        } else if (i == SerializationHeader.HTREE_DIRECTORY) {
          HTreeDirectory ret = new HTreeDirectory(HTree.this);
          ret.readExternal(ds);
          if (loadValues && ds.available() != 0)
            throw new InternalError("bytes left: " + ds.available());
          return ret;
        } else {
          throw new InternalError("Wrong HTree header: " + i);
        }
      } catch (ClassNotFoundException e) {
        throw new IOException(e);
      }

    }

    @Override
    public void serialize(DataOutput out, Object obj) throws IOException {
      if (obj instanceof HTreeBucket) {
        out.write(SerializationHeader.HTREE_BUCKET);
        HTreeBucket b = (HTreeBucket) obj;
        b.writeExternal(out);
      } else {
        out.write(SerializationHeader.HTREE_DIRECTORY);
        HTreeDirectory n = (HTreeDirectory) obj;
        n.writeExternal(out);
      }
    }
  };

  /**
   * Listeners which are notified about changes in records
   */
  protected RecordListener[] recordListeners = new RecordListener[0];

  /**
   * Serializer used to serialize index keys (optional)
   */
  protected Serializer<K> keySerializer;

  /**
   * Serializer used to serialize index values (optional)
   */
  protected Serializer<V> valueSerializer;
  protected boolean readonly = false;
  final long rootRecid;
  DBAbstract db;
  /** if false map contains only keys, used for set */
  boolean hasValues = true;

  /**
   * counts structural changes in tree at runtume. Is here to support fail-fast
   * behaviour.
   */
  int modCount;

  /**
   * indicates if values should be loaded during deserialization, set to true
   * during defragmentation
   */
  private boolean loadValues = true;

  public Serializer<K> getKeySerializer() {
    return keySerializer;
  }

  public Serializer<V> getValueSerializer() {
    return valueSerializer;
  }

  /**
   * cache writing buffer, so it does not have to be allocated on each write
   */
  AtomicReference<DataInputOutput> writeBufferCache = new AtomicReference<DataInputOutput>();

  /**
   * Create a persistent hashtable.
   */
  @SuppressWarnings("unchecked")
  public HTree(DBAbstract db, Serializer<K> keySerializer,
      Serializer<V> valueSerializer, boolean hasValues) throws IOException {
    this.keySerializer = keySerializer;
    this.valueSerializer = valueSerializer;
    this.db = db;
    this.hasValues = hasValues;

    HTreeDirectory<K, V> root = new HTreeDirectory<K, V>(this, (byte) 0);
    root.setPersistenceContext(0);
    this.rootRecid = db.insert(root, this.SERIALIZER, false);
  }

  /**
   * Load a persistent hashtable
   */
  public HTree(DBAbstract db, long rootRecid, Serializer<K> keySerializer,
      Serializer<V> valueSerializer, boolean hasValues) throws IOException {
    this.db = db;
    this.rootRecid = rootRecid;
    this.keySerializer = keySerializer;
    this.valueSerializer = valueSerializer;
    this.hasValues = hasValues;
  }

  void setPersistenceContext(DBAbstract db) {
    this.db = db;
  }

  @SuppressWarnings("unchecked")
  @Override
  public V put(K key, V value) {
    if (readonly)
      throw new UnsupportedOperationException("readonly");
    try {
      if (key == null || value == null)
        throw new NullPointerException("Null key or value");

      V oldVal = (V) getRoot().put(key, value);
      if (oldVal == null) {
        modCount++;

        // increase size
        HTreeDirectory root = getRoot();
        root.size++;
        db.update(rootRecid, root, SERIALIZER);

        for (RecordListener<K, V> r : recordListeners)
          r.recordInserted(key, value);
      } else {

        // notify listeners
        for (RecordListener<K, V> r : recordListeners)
          r.recordUpdated(key, oldVal, value);
      }

      return oldVal;
    } catch (IOException e) {
      throw new IOError(e);
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public V get(Object key) {
    if (key == null)
      return null;
    try {
      return getRoot().get((K) key);
    } catch (ClassCastException e) {
      return null;
    } catch (IOException e) {
      throw new IOError(e);
    }

  }

  @SuppressWarnings("unchecked")
  @Override
  public V remove(Object key) {
    if (readonly)
      throw new UnsupportedOperationException("readonly");

    try {
      if (key == null)
        return null;

      V val = (V) getRoot().remove(key);
      modCount++;

      if (val != null) {
        // decrease size
        HTreeDirectory root = getRoot();
        root.size--;
        db.update(rootRecid, root, SERIALIZER);

        for (RecordListener r : recordListeners)
          r.recordRemoved(key, val);
      }

      return val;
    } catch (ClassCastException e) {
      return null;
    } catch (IOException e) {
      throw new IOError(e);
    }

  }

  @Override
  public boolean containsKey(Object key) {
    if (key == null)
      return false;
    // no need for locking, get is already locked
    V v = get(key);
    return v != null;
  }

  @Override
  public void clear() {
    try {
      Iterator<K> keyIter = keys();
      while (keyIter.hasNext()) {
        keyIter.next();
        keyIter.remove();
      }
    } catch (IOException e) {
      throw new IOError(e);
    }

  }

  /**
   * Returns an enumeration of the keys contained in this
   */
  public Iterator<K> keys() throws IOException {
    return getRoot().keys();
  }

  public DBAbstract getRecordManager() {
    return db;
  }

  /**
   * add RecordListener which is notified about record changes
   * 
   * @param listener
   */
  public void addRecordListener(RecordListener<K, V> listener) {
    recordListeners = Arrays
        .copyOf(recordListeners, recordListeners.length + 1);
    recordListeners[recordListeners.length - 1] = listener;
  }

  /**
   * remove RecordListener which is notified about record changes
   * 
   * @param listener
   */
  @SuppressWarnings("unchecked")
  public void removeRecordListener(RecordListener<K, V> listener) {
    List l = Arrays.asList(recordListeners);
    l.remove(listener);
    recordListeners = (RecordListener[]) l.toArray(new RecordListener[1]);
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    return _entrySet;
  }

  private Set<Entry<K, V>> _entrySet = new AbstractSet<Entry<K, V>>() {

    protected Entry<K, V> newEntry(K k, V v) {
      return new SimpleEntry<K, V>(k, v) {
        private static final long serialVersionUID = 978651696969194154L;

        @Override
        public V setValue(V arg0) {
          // put is already locked
          HTree.this.put(getKey(), arg0);
          return super.setValue(arg0);
        }

      };
    }

    @Override
    public boolean add(java.util.Map.Entry<K, V> e) {
      if (readonly)
        throw new UnsupportedOperationException("readonly");
      if (e.getKey() == null)
        throw new NullPointerException("Can not add null key");
      if (e.getValue().equals(get(e.getKey())))
        return false;
      HTree.this.put(e.getKey(), e.getValue());
      return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean contains(Object o) {
      if (o instanceof Entry) {
        Entry<K, V> e = (java.util.Map.Entry<K, V>) o;

        // get is already locked
        if (e.getKey() != null && HTree.this.get(e.getKey()) != null)
          return true;
      }
      return false;
    }

    @Override
    public Iterator<java.util.Map.Entry<K, V>> iterator() {
      try {
        final Iterator<K> br = keys();
        return new Iterator<Entry<K, V>>() {

          @Override
          public boolean hasNext() {
            return br.hasNext();
          }

          @Override
          public java.util.Map.Entry<K, V> next() {
            K k = br.next();
            return newEntry(k, get(k));
          }

          @Override
          public void remove() {
            if (readonly)
              throw new UnsupportedOperationException("readonly");
            br.remove();
          }
        };

      } catch (IOException e) {
        throw new IOError(e);
      }

    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean remove(Object o) {
      if (readonly)
        throw new UnsupportedOperationException("readonly");

      if (o instanceof Entry) {
        Entry<K, V> e = (java.util.Map.Entry<K, V>) o;

        // check for nulls
        if (e.getKey() == null || e.getValue() == null)
          return false;
        // get old value, must be same as item in entry
        V v = get(e.getKey());
        if (v == null || !e.getValue().equals(v))
          return false;
        HTree.this.remove(e.getKey());
        return true;
      }
      return false;

    }

    @Override
    public int size() {
      try {
        int counter = 0;
        Iterator<K> it = keys();
        while (it.hasNext()) {
          it.next();
          counter++;
        }
        return counter;
      } catch (IOException e) {
        throw new IOError(e);
      }

    }

  };

  @SuppressWarnings("unchecked")
  HTreeDirectory<K, V> getRoot() {
    // assumes that caller already holds read or write lock
    try {
      HTreeDirectory<K, V> root = (HTreeDirectory<K, V>) db.fetch(rootRecid,
          this.SERIALIZER);
      root.setPersistenceContext(rootRecid);
      return root;
    } catch (IOException e) {
      throw new IOError(e);
    }
  }

  @SuppressWarnings("unchecked")
  public static HTree deserialize(DataInput is, Serialization ser)
      throws IOException, ClassNotFoundException {
    long rootRecid = LongPacker.unpackLong(is);
    boolean hasValues = is.readBoolean();
    Serializer keySerializer = (Serializer) ser.deserialize(is);
    Serializer valueSerializer = (Serializer) ser.deserialize(is);

    return new HTree(ser.db, rootRecid, keySerializer, valueSerializer,
        hasValues);
  }

  @SuppressWarnings("unchecked")
  void serialize(DataOutput out) throws IOException {
    LongPacker.packLong(out, rootRecid);
    out.writeBoolean(hasValues);
    db.defaultSerializer().serialize(out, keySerializer);
    db.defaultSerializer().serialize(out, valueSerializer);
  }

  @SuppressWarnings("unchecked")
  static void defrag(Long recid, DBStore r1, DBStore r2) throws IOException {
    // TODO should modCount be increased after defrag, revert or commit?
    try {
      byte[] data = r1.fetchRaw(recid);
      r2.forceInsert(recid, data);
      DataInput in = new DataInputStream(new ByteArrayInputStream(data));
      HTree t = (HTree) r1.defaultSerializer().deserialize(in);
      t.db = r1;
      t.loadValues = false;

      HTreeDirectory d = t.getRoot();
      if (d != null) {
        r2.forceInsert(t.rootRecid, r1.fetchRaw(t.rootRecid));
        d.defrag(r1, r2);
      }

    } catch (ClassNotFoundException e) {
      throw new IOError(e);
    }

  }

  @Override
  public int size() {
    return (int) getRoot().size;
  }

  public boolean hasValues() {
    return hasValues;
  }

}

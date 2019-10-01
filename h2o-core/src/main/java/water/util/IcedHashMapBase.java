package water.util;

import water.AutoBuffer;
import water.Freezable;
import water.H2O;
import water.Iced;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.apache.commons.lang.ArrayUtils.toObject;
import static org.apache.commons.lang.ArrayUtils.toPrimitive;

/**
 * Iced / Freezable NonBlockingHashMap abstract base class.
 */
public abstract class IcedHashMapBase<K, V> extends Iced implements Map<K, V>, Cloneable, Serializable {

  enum KeyType {
    String(String.class),
    Freezable(Freezable.class),
    ;

    Class _clazz;
    KeyType(Class clazz) {
      _clazz = clazz;
    }
  }

  enum ValueType {
    String(String.class),
    Freezable(Freezable.class),
    Boolean(Boolean.class),
    Integer(Integer.class),
    Long(Long.class),
    Float(Float.class),
    Double(Double.class),
    ;

    Class _clazz;
    Class _arrayClazz;
    ValueType(Class clazz) {
      _clazz = clazz;
      _arrayClazz = Array.newInstance(_clazz, 0).getClass();
    }
  }

  private transient volatile boolean _write_lock;
  abstract protected Map<K,V> map();
  public int size()                                     { return map().size(); }
  public boolean isEmpty()                              { return map().isEmpty(); }
  public boolean containsKey(Object key)                { return map().containsKey(key); }
  public boolean containsValue(Object value)            { return map().containsValue(value); }
  public V get(Object key)                              { return (V)map().get(key); }
  public V put(K key, V value)                          { assert !_write_lock; return (V)map().put(key, value);}
  public V remove(Object key)                           { assert !_write_lock; return map().remove(key); }
  public void putAll(Map<? extends K, ? extends V> m)   { assert !_write_lock;        map().putAll(m); }
  public void clear()                                   { assert !_write_lock;        map().clear(); }
  public Set<K> keySet()                                { return map().keySet(); }
  public Collection<V> values()                         { return map().values(); }
  public Set<Entry<K, V>> entrySet()                    { return map().entrySet(); }
  @Override public boolean equals(Object o)             { return map().equals(o); }
  @Override public int hashCode()                       { return map().hashCode(); }
  @Override public String toString()                    { return map().toString(); }


  protected boolean isArrayVal(byte mode) { return (mode & 1) == 1; } // 1st bit encodes if value is array
  protected ValueType valueType(byte mode) { return ValueType.values()[mode>>>1 & 0xF];} //2nd to 5th bit encodes value type
  protected KeyType keyType(byte mode) { return KeyType.values()[mode>>>5 & 1]; } // 6th bit encodes key type

  protected byte getMode(KeyType keyType, ValueType valueType, boolean valueIsArray) {
    return (byte) ((keyType.ordinal() << 5) + (valueType.ordinal() << 1) + (valueIsArray ? 1 : 0));
  }

  protected KeyType getKeyType(K key) {
    assert key != null;
    return Stream.of(KeyType.values())
            .filter(t -> isValidKey(key, t))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("keys of type "+key.getClass().getTypeName()+" are not supported"));
  }

  protected ValueType getValueType(V value) {
    boolean isArray = value != null && value.getClass().isArray();
    return Stream.of(ValueType.values())
            .filter(t -> isValidValue(value, t, isArray))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("values of type "+value.getClass().getTypeName()+" are not supported"));
  }

  protected boolean isValidKey(K key, KeyType keyType) {
    return keyType._clazz.isInstance(key);
  }

  protected boolean isValidValue(V value, ValueType valueType, boolean isArray) {
    return isArray ? valueType._arrayClazz.isInstance(value) : valueType._clazz.isInstance(value);
  }

  // This comment is stolen from water.parser.Categorical:
  //
  // Since this is a *concurrent* hashtable, writing it whilst its being
  // updated is tricky.  If the table is NOT being updated, then all is written
  // as expected.  If the table IS being updated we only promise to write the
  // Keys that existed at the time the table write began.  If elements are
  // being deleted, they may be written anyways.  If the Values are changing, a
  // random Value is written.
  public final AutoBuffer write_impl( AutoBuffer ab ) {
    _write_lock = true;
    try {
      if (map().size() == 0) return ab.put1(-1); // empty map
      Entry<K, V> entry = map().entrySet().iterator().next();
      K key = entry.getKey();
      V val = entry.getValue();
      assert key != null && val != null;
      byte mode = getMode(getKeyType(key), getValueType(val), val.getClass().isArray());
      ab.put1(mode);              // Type of hashmap being serialized
      writeMap(ab, mode);          // Do the hard work of writing the map
      switch (keyType(mode)) {
        case String:
          return ab.putStr(null);
        case Freezable:
        default:
          return ab.put(null);
      }
    } catch(Throwable t){
      System.err.println("Iced hash map serialization failed! " + t.toString() + ", msg = " + t.getMessage());
      t.printStackTrace();
      throw H2O.fail("Iced hash map serialization failed!" + t.toString() + ", msg = " + t.getMessage());
    } finally {
      _write_lock = false;
    }
  }

  abstract protected Map<K,V> init();

  protected void writeMap(AutoBuffer ab, byte mode) {
    KeyType keyType = keyType(mode);
    ValueType valueType = valueType(mode);
    for( Entry<K, V> e : map().entrySet() ) {
      K key = e.getKey();   assert key != null;
      V val = e.getValue(); assert val != null;

      writeKey(ab, keyType, key);
      writeValue(ab, valueType, val);
    }
  }

  protected void writeKey(AutoBuffer ab, KeyType keyType, K key) {
    switch (keyType) {
      case String:      ab.putStr((String)key); break;
      case Freezable:   ab.put((Freezable)key); break;
    }
  }

  protected void writeValue(AutoBuffer ab, ValueType valueType, V value) {
    boolean isArray = value != null && value.getClass().isArray();
    if (isArray) {
      switch(valueType) {
        case String:    ab.putAStr((String[])value); break;
        case Freezable: ab.putA((Freezable[])value); break;
        case Boolean:   ab.putA1(toPrimitive(Stream.of((Boolean[])value).map(b -> b?(byte)1:0).toArray(Byte[]::new))); break;
        case Integer:   ab.putA4(toPrimitive((Integer[])value)); break;
        case Long:      ab.putA8(toPrimitive((Long[])value)); break;
        case Float:     ab.putA4f(toPrimitive((Float[])value)); break;
        case Double:    ab.putA8d(toPrimitive((Double[])value)); break;
      }
    } else {
      switch(valueType) {
        case String:    ab.putStr((String)value); break;
        case Freezable: ab.put((Freezable)value); break;
        case Boolean:   ab.put1((Boolean)value ? 1 : 0); break;
        case Integer:   ab.put4((Integer)value); break;
        case Long:      ab.put8((Long)value); break;
        case Float:     ab.put4f((Float)value); break;
        case Double:    ab.put8d((Double)value); break;
      }
    }
  }

  @SuppressWarnings("unchecked")
  protected K readKey(AutoBuffer ab, KeyType keyType) {
    switch (keyType) {
      case String: return (K) ab.getStr();
      case Freezable: return ab.get();
      default: return null;
    }
  }

  @SuppressWarnings("unchecked")
  protected V readValue(AutoBuffer ab, ValueType valueType, boolean isArray) {
    if (isArray) {
      switch(valueType) {
        case String:    return (V) ab.getAStr();
        case Freezable: return (V) ab.getA(Freezable.class);
        case Boolean:   return (V) Stream.of(toObject(ab.getA1())).map(b -> b == 1).toArray(Boolean[]::new);
        case Integer:   return (V) toObject(ab.getA4());
        case Long:      return (V) toObject(ab.getA8());
        case Float:     return (V) toObject(ab.getA4f());
        case Double:    return (V) toObject(ab.getA8d());
        default:        return null;
      }
    } else {
      switch(valueType) {
        case String:    return (V) ab.getStr();
        case Freezable: return (V) ab.get();
        case Boolean:   return (V) Boolean.valueOf(ab.get1() == 1);
        case Integer:   return (V) Integer.valueOf(ab.get4());
        case Long:      return (V) Long.valueOf(ab.get8());
        case Float:     return (V) Float.valueOf(ab.get4f());
        case Double:    return (V) Double.valueOf(ab.get8d());
        default:        return null;
      }
    }
  }

  /**
   * Helper for serialization - fills the mymap() from K-V pairs in the AutoBuffer object
   * @param ab Contains the serialized K-V pairs
   */
  public final IcedHashMapBase read_impl(AutoBuffer ab) {
    try {
      assert map() == null || map().isEmpty(); // Fresh from serializer, no constructor has run
      Map<K, V> map = init();
      byte mode = ab.get1();
      if (mode < 0) return this;
      KeyType keyType = keyType(mode);
      ValueType valueType = valueType(mode);
      boolean arrayVal = isArrayVal(mode);

      while (true) {
        K key = readKey(ab, keyType);
        if (key == null) break;
        V val = readValue(ab, valueType, arrayVal);
        map.put(key, val);
      }
      return this;
    } catch(Throwable t) {
      t.printStackTrace();

      if (null == t.getCause()) {
        throw H2O.fail("IcedHashMap deserialization failed! + " + t.toString() + ", msg = " + t.getMessage() + ", cause: null");
      } else {
        throw H2O.fail("IcedHashMap deserialization failed! + " + t.toString() + ", msg = " + t.getMessage() +
                ", cause: " + t.getCause().toString() +
                ", cause msg: " + t.getCause().getMessage() +
                ", cause stacktrace: " + java.util.Arrays.toString(t.getCause().getStackTrace()));
      }
    }
  }

  public final IcedHashMapBase readJSON_impl( AutoBuffer ab ) {throw H2O.unimpl();}

  public final AutoBuffer writeJSON_impl( AutoBuffer ab ) {
    boolean first = true;
    for (Entry<K, V> entry : map().entrySet()) {
      K key = entry.getKey();
      V value = entry.getValue();

      KeyType keyType = getKeyType(key);
      assert keyType == KeyType.String: "JSON format supports only String keys";
      ValueType valueType = getValueType(value);

      if (first) { first = false; } else {ab.put1(',').put1(' '); }
      String name = (String) key;
      boolean isArray = value != null && value.getClass().isArray();
      if (isArray) {
        switch (valueType) {
          case String:    ab.putJSONAStr(name, (String[]) value); break;
          case Freezable: ab.putJSONA(name, (Freezable[]) value); break;
          case Boolean:   ab.putJSONStrUnquoted(name, Arrays.toString(toPrimitive((Boolean[]) value))); break;
          case Integer:   ab.putJSONA4(name, toPrimitive((Integer[]) value)); break;
          case Long:      ab.putJSONA8(name, toPrimitive((Long[]) value)); break;
          case Float:     ab.putJSONA4f(name, toPrimitive((Float[]) value)); break;
          case Double:    ab.putJSONA8d(name, toPrimitive((Double[]) value)); break;
        }
      } else {
        switch (valueType) {
          case String:    ab.putJSONStr(name, (String) value); break;
          case Freezable: ab.putJSON(name, (Freezable) value); break;
          case Boolean:   ab.putJSONStrUnquoted(name, Boolean.toString((Boolean)value)); break;
          case Integer:   ab.putJSON4(name, (Integer) value); break;
          case Long:      ab.putJSON8(name, (Long) value); break;
          case Float:     ab.putJSON4f(name, (Float) value); break;
          case Double:    ab.putJSON8d(name, (Double) value); break;
        }
      }
    }
    return ab;
  }
}

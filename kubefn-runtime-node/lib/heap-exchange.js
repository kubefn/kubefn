/**
 * HeapExchange — Zero-copy shared object store for Node.js functions.
 *
 * All functions in the same V8 isolate share the same HeapExchange.
 * Objects are JS references — no serialization, no copying.
 * Function A publishes an object, Function B reads the SAME object.
 */

class HeapCapsule {
  constructor(key, value, valueType, version, publisherGroup, publisherFunction) {
    this.key = key;
    this.value = value;
    this.valueType = valueType;
    this.version = version;
    this.publisherGroup = publisherGroup;
    this.publisherFunction = publisherFunction;
    this.publishedAt = Date.now();
  }
}

class HeapExchange {
  constructor(maxObjects = 10000) {
    this._store = new Map();
    this._maxObjects = maxObjects;
    this._versionCounter = 0;

    // Metrics
    this.publishCount = 0;
    this.getCount = 0;
    this.hitCount = 0;
    this.missCount = 0;

    // Context
    this._currentGroup = null;
    this._currentFunction = null;

    // Audit log
    this._auditLog = [];
    this._maxAudit = 10000;
  }

  setContext(group, fn) {
    this._currentGroup = group;
    this._currentFunction = fn;
  }

  clearContext() {
    this._currentGroup = null;
    this._currentFunction = null;
  }

  publish(key, value, valueType = 'object') {
    if (this._store.size >= this._maxObjects && !this._store.has(key)) {
      throw new Error(`HeapExchange at capacity (${this._maxObjects} objects)`);
    }

    this._versionCounter++;
    const capsule = new HeapCapsule(
      key, value, valueType, this._versionCounter,
      this._currentGroup || 'unknown',
      this._currentFunction || 'unknown'
    );

    this._store.set(key, capsule);
    this.publishCount++;
    this._audit('PUBLISH', key, valueType);
    return capsule;
  }

  get(key) {
    this.getCount++;
    const capsule = this._store.get(key);

    if (!capsule) {
      this.missCount++;
      this._audit('GET_MISS', key);
      return undefined;
    }

    this.hitCount++;
    this._audit('GET_HIT', key, capsule.valueType);

    // Zero copy: return the SAME object reference
    return capsule.value;
  }

  getCapsule(key) {
    return this._store.get(key);
  }

  remove(key) {
    const existed = this._store.delete(key);
    if (existed) this._audit('REMOVE', key);
    return existed;
  }

  keys() { return [...this._store.keys()]; }
  contains(key) { return this._store.has(key); }
  size() { return this._store.size; }

  metrics() {
    const hitRate = this.getCount > 0
      ? ((this.hitCount / this.getCount) * 100).toFixed(2) + '%' : '0.00%';
    return {
      objectCount: this._store.size,
      publishCount: this.publishCount,
      getCount: this.getCount,
      hitCount: this.hitCount,
      missCount: this.missCount,
      hitRate,
      keys: this.keys(),
    };
  }

  _audit(action, key, type = null) {
    this._auditLog.push({
      action, key, type,
      group: this._currentGroup,
      function: this._currentFunction,
      timestamp: Date.now(),
    });
    if (this._auditLog.length > this._maxAudit) {
      this._auditLog = this._auditLog.slice(-this._maxAudit);
    }
  }
}

module.exports = { HeapExchange, HeapCapsule };

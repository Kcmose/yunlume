package com.example.nav.module.publicdata;

import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/** 每个公开缓存只保留最新一代；旧请求在失效后返回时不能重新填入旧代。 */
final class PublicDataGenerationCache extends AbstractValueAdaptingCache {

    private static final Pattern GENERATION_KEY = Pattern.compile("0|[1-9][0-9]{0,9}");

    private final String name;
    private final ConcurrentMap<Object, Object> values = new ConcurrentHashMap<>();
    private long generation = -1;

    PublicDataGenerationCache(String name, boolean allowNullValues) {
        super(allowNullValues);
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ConcurrentMap<Object, Object> getNativeCache() {
        return values;
    }

    @Override
    protected synchronized Object lookup(Object key) {
        return keyGeneration(key) == generation && generation >= 0 ? values.get(key) : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper cached = get(key);
        if (cached != null) return (T) cached.get();
        try {
            // 查询在锁外执行，业务提交可推进版本；put 会重新核对这次读取的归属。
            T loaded = valueLoader.call();
            put(key, loaded);
            return loaded;
        } catch (Exception exception) {
            throw new ValueRetrievalException(key, valueLoader, exception);
        }
    }

    @Override
    public void put(Object key, Object value) {
        afterCommitOrNow(() -> putCommitted(key, value));
    }

    private synchronized void putCommitted(Object key, Object value) {
        if (accept(key)) values.put(key, toStoreValue(value));
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        if (inTransaction()) {
            ValueWrapper previous = get(key);
            afterCommitOrNow(() -> putIfAbsentCommitted(key, value));
            return previous;
        }
        return putIfAbsentCommitted(key, value);
    }

    private synchronized ValueWrapper putIfAbsentCommitted(Object key, Object value) {
        return accept(key) ? toValueWrapper(values.putIfAbsent(key, toStoreValue(value))) : null;
    }

    @Override
    public synchronized void evict(Object key) {
        if (key != null) values.remove(key);
    }

    @Override
    public synchronized boolean evictIfPresent(Object key) {
        return key != null && values.remove(key) != null;
    }

    @Override
    public synchronized void clear() {
        values.clear();
    }

    @Override
    public synchronized boolean invalidate() {
        boolean present = !values.isEmpty();
        values.clear();
        return present;
    }

    synchronized void advanceTo(long next) {
        if (next > generation) {
            values.clear();
            generation = next;
        }
    }

    private boolean accept(Object key) {
        long requested = keyGeneration(key);
        if (requested < 0 || requested < generation) return false;
        advanceTo(requested);
        return true;
    }

    private long keyGeneration(Object key) {
        // 公开接口实际使用十进制字符串；无效 key 不缓存，不能破坏当前代或导致请求异常。
        if (!(key instanceof String text) || !GENERATION_KEY.matcher(text).matches()) return -1;
        long requested = Long.parseLong(text);
        return requested > Integer.MAX_VALUE ? -1 : requested;
    }

    private boolean inTransaction() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive();
    }

    private void afterCommitOrNow(Runnable publication) {
        if (!inTransaction()) {
            publication.run();
            return;
        }
        // 事务内公开读取也可能看到未提交版本；回滚后不能留下这代缓存或推进最低代。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publication.run();
            }
        });
    }
}

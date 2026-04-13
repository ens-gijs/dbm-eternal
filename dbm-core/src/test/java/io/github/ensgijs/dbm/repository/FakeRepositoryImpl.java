package io.github.ensgijs.dbm.repository;

import io.github.ensgijs.dbm.sql.SqlClient;

import java.util.HashMap;
import java.util.Map;

public class FakeRepositoryImpl extends AbstractRepository implements FakeRepository {
    public Map<Integer, String> map = new HashMap<>();
    public boolean invalidateCacheCalled = false;
    public boolean poisonInvalidateCache = false;

    public FakeRepositoryImpl(SqlClient sqlClient) {
        super(sqlClient);
    }

    @Override
    public void invalidateCaches() {
        invalidateCacheCalled = true;
        if (poisonInvalidateCache) {
            throw new IllegalStateException("POISONED");
        }
        super.invalidateCaches();
    }

    @Override
    public void put(int key, String value) {
       map.put(key, value);
    }

    @Override
    public String get(int key) {
        return map.get(key);
    }

    @Override
    public void clear() {
        map.clear();
    }
}

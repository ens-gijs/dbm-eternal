package io.github.ensgijs.dbm.repository;

@RepositoryApi("fake-repo")
public interface FakeRepository extends Repository {
    void put(int key, String value);
    String get(int key);
    void clear();
}

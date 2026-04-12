# dbm-eternal

A platform-agnostic SQL database management library providing connection pooling,
schema migrations, and a repository pattern with a plugin-friendly voting/election system.

Designed to be general-purpose but with first-class support for multi-plugin environments
such as [Paper](https://papermc.io/) and [Velocity](https://velocitypowered.com/) Minecraft servers.

## Modules

| Module | Description |
|---|---|
| `dbm-sql` | Core SQL layer — connection pooling (HikariCP), statement execution, batch/upsert helpers, async support, utilities |
| `dbm-core` | Schema migration engine + repository registry built on `dbm-sql` |
| `dbm-platform-paper` | Paper (Minecraft) platform integration _(in progress)_ |
| `dbm-platform-velocity` | Velocity (Minecraft) platform integration _(in progress)_ |

## Quick Start

### 1. Add a dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.ensgijs.dbm:dbm-core:1.0.0-SNAPSHOT")
}
```

`dbm-core` transitively includes `dbm-sql`. Use `dbm-sql` alone if you only need the SQL
layer without migrations or the repository registry.

### 2. Configure a connection

```java
// MySQL (or MariaDB)
SqlConnectionConfig mysqlConfig = new MySqlConnectionConfig(
    "127.0.0.1", 3306, "my_database", /*maxConnections*/ 10, "user", "password");

// SQLite — takes a File directly; use the .of() factory to derive the path from a folder + name
SqlConnectionConfig sqliteConfig = new SqliteConnectionConfig(new File("data/my_database.db"));
// or equivalently:
SqlConnectionConfig sqliteConfig = SqliteConnectionConfig.of(new File("data"), "my_database");
```

### 3. Run queries with `SqlClient`

```java
PlatformHandle platform = new SimplePlatformHandle("MyApp", List.of());
SqlClient db = new SqlClient(platform, mysqlConfig);

// Single update
db.executeUpdate("INSERT INTO greetings (msg) VALUES (?)", "hello");

// Single query
String msg = db.executeQuery(
    "SELECT msg FROM greetings WHERE id = ?",
    rs -> rs.next() ? rs.getString("msg") : null,
    1);

// Multiple operations on one connection (session = auto-commit per statement)
db.executeSession(ctx -> {
    int count = ctx.executeQuery("SELECT COUNT(*) FROM greetings", rs -> {
        rs.next(); return rs.getInt(1);
    });
    if (count < 100) {
        ctx.executeUpdate("INSERT INTO greetings (msg) VALUES (?)", "world");
    }
    return null;
});

// Atomic block (transaction = commit or rollback together)
db.executeTransaction(ctx -> {
    ctx.executeUpdate("UPDATE accounts SET balance = balance - ? WHERE id = ?", 50, fromId);
    ctx.executeUpdate("UPDATE accounts SET balance = balance + ? WHERE id = ?", 50, toId);
    return null;
});

// Batch inserts
db.executeSession(ctx -> {
    List<Object[]> rows = List.of(new Object[]{"a"}, new Object[]{"b"});
    ctx.executeBatch("INSERT INTO greetings (msg) VALUES (?)", rows);
    return null;
});
```

### 4. Define migrations

Place SQL files in `src/main/resources/db/migrate/`. The naming convention is:

```
{name}.{version}[.{dialect}].{ext}
```

- **`name`** — migration area (e.g., `users`, `core`)
- **`version`** — numeric, typically a Unix timestamp; underscores/dashes are stripped
- **`dialect`** — `mysql` or `sqlite` (required for `.sql` files; omitted for `.run` files)
- **`ext`** — `sql` for raw SQL or `run` for a programmatic Java migration

Examples:
```
db/migrate/users.20240101.mysql.sql
db/migrate/users.20240101.sqlite.sql
db/migrate/core.1700000000.run
```

Dialect-independent SQL files use the `.run` extension and contain a single fully-qualified
Java class name implementing `Migration.ProgrammaticMigration`.

#### Dependency directives

Use `!AFTER` at the top of a migration file to declare that it must run after another:

```sql
-- !AFTER: core.1700000000
CREATE TABLE users (
    id   BIGINT PRIMARY KEY,
    name VARCHAR(255) NOT NULL
);
```

### 5. Define a Repository

```java
@RepositoryApi("users")
public interface UserRepository extends Repository {
    void save(User user);
    Optional<User> findById(long id);
}

public class UserRepositoryImpl extends AbstractRepository implements UserRepository {
    public UserRepositoryImpl(SqlDatabaseManager db) {
        super(db);
    }

    @Override
    public void save(User user) {
        getDatabaseManager().executeUpdate(
            "INSERT INTO users (id, name) VALUES (?, ?)", user.id(), user.name());
    }

    @Override
    public Optional<User> findById(long id) {
        return getDatabaseManager().executeQuery(
            "SELECT id, name FROM users WHERE id = ?",
            rs -> rs.next() ? Optional.of(new User(rs.getLong("id"), rs.getString("name")))
                            : Optional.empty(),
            id);
    }
}
```

Register the implementation by placing a file in `src/main/resources/db/registry/` whose
filename is the fully-qualified class name of the implementation class:

```
db/registry/com.example.myplugin.UserRepositoryImpl
```

### 6. Bootstrap the registry

```java
RepositoryRegistry registry = RepositoryRegistry.globalRegistry();

registry.register(platform, MyPlugin.class.getClassLoader())
    .onConfigure(ctx -> {
        ctx.nominateDefaultProvider(UserRepository.class, myDatabaseManager);
    })
    .onReady(reg -> {
        UserRepository users = reg.getDefaultRepository(UserRepository.class);
        // use repos...
    });

registry.closeRegistration();
```

## Upsert helper

`UpsertStatement` generates dialect-correct `INSERT ... ON CONFLICT` / `ON DUPLICATE KEY UPDATE` SQL:

```java
private static final UpsertStatement UPSERT = UpsertStatement.builder()
    .table("user_scores")
    .keys("user_id")
    .values("score", "updated_at")
    .build();

// Single upsert
db.executeUpsert(UPSERT, userId, score, Instant.now());

// Batch upsert inside a transaction
db.executeTransaction(ctx -> {
    ctx.executeBatch(db.sql(UPSERT), rows);
    return null;
});
```

## License

Apache 2.0 — see [LICENSE](LICENSE).

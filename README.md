# dbm-eternal

A platform-agnostic SQL database management library providing connection pooling,
schema migrations, and a repository pattern with a plugin-friendly registry that
resolves provider conflicts between modules.

## Modules

| Module | Description |
|---|---|
| `dbm-sql` | Core SQL layer — connection pooling (HikariCP), statement execution, batch/upsert helpers, async support, utilities |
| `dbm-core` | Schema migration engine + repository registry built on `dbm-sql` |

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

// In-memory SQLite DB
SqlConnectionConfig inMemConfig = SqliteConnectionConfig.inMemory();
```

### 3. Run queries with `SqlClient`

```java
PlatformHandle platform = new SimplePlatformHandle("MyApp");
SqlClient client = new SqlClient(platform, mysqlConfig);

// Single updates
client.executeUpdate("CREATE TABLE IF NOT EXISTS greetings (id INTEGER PRIMARY KEY ASC, msg VARCHAR(64))");
client.executeUpdate("INSERT INTO greetings (msg) VALUES (?)", "hello");

// Check if a table exists
if (client.tableExists("users")) {
    // ...
}
// Check if a table column exists
if (client.tableHasColumn("users", "full_name")) {
    // ...
}

// Single query
String msg = client.executeQuery(
    "SELECT msg FROM greetings WHERE id = ?",
    rs -> rs.next() ? rs.getString("msg") : null,
    1);

// Query producing list of strongly typed results
record KnownMessage(int id, String msg) {};
List<KnownMessage> msgs = client.executeQuery(
    "SELECT id, msg FROM greetings WHERE id BETWEEN ? AND ?",
    rs -> {
        List<KnownMessage> out = new ArrayList<>();
        while (rs.next()) {
            out.add(new KnownMessage(rs.getInt("id"), rs.getString("msg")));
        }
        return out;
    },
    1, 3);

// Multiple operations on one connection (session = auto-commit per statement)
client.executeSession(ctx -> {
    int count = ctx.executeQuery("SELECT COUNT(*) FROM greetings", rs -> {
        rs.next(); return rs.getInt(1);
    });
    if (count < 100) {
        ctx.executeUpdate("INSERT INTO greetings (msg) VALUES (?)", "world");
    }
    return null;
});

// Atomic block (transaction = commit or rollback together)
client.executeTransaction(ctx -> {
    ctx.executeUpdate("UPDATE accounts SET balance = balance - ? WHERE id = ?", 50, fromId);
    ctx.executeUpdate("UPDATE accounts SET balance = balance + ? WHERE id = ?", 50, toId);
    return null;
});

// Batch inserts — atomic operation, use chunked batch for batching over ~500-5k updates.
List<Object[]> rows = List.of(new Object[]{"a"}, new Object[]{"b"});
client.executeBatch("INSERT INTO greetings (msg) VALUES (?)", rows);

// Batch inserts using object converter
record Message(String msg) {};
List<Message> messages = List.of(new Message("one"), new Message("two"));
client.executeBatch("INSERT INTO greetings (msg) VALUES (?)", messages, (m, cols) -> cols[0] = m.msg());

// Chunking batches of unbound size
// — Each chunk is executed in its own transaction for optimal perfomance.
// — Each chunk is atomic, overall batch is NOT atomic, see javadocs for recovery options.
// — An object transformer overload exists for this function.
client.executeChunkedBatch(maxChunkingSize, sql, ...);

// Chunking batches of bound size
// — Full batch is atomic - all chunks are run within a single transaction.
// — WARNING: May cause database undo/redo logs to growing indefinitely if used
//   for extremely large batches — may degrade performance or lead to crashes.
client.executeTransaction(ctx -> {
    // ctx.executeChunkedBatch will always execute each chunk in its own transaction for optimal performance.
    // The first failed chunk throws immediatly, see javadocs for recovery options.
    // An object transformer overload exists for this function.
    return ctx.executeChunkedBatch(maxChunkingSize, sql, ...);
});

```

> [!TIP]
> Async variants exist for all execute() operations. \
> Each SqlClient contains a parallelism bounded virtual thread pool for running async operations.

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

@RepositoryImpl(SqlDialect.SQLITE)
public class UserRepositoryImpl extends AbstractRepository implements UserRepository {
    public UserRepositoryImpl(SqlClient db) {
        super(db);
    }

    @Override
    public void save(User user) {
        sqlClient.executeUpdate(
            "INSERT INTO users (id, name) VALUES (?, ?)", user.id(), user.name());
    }

    @Override
    public Optional<User> findById(long id) {
        return sqlClient.executeQuery(
            "SELECT id, name FROM users WHERE id = ?",
            rs -> rs.next() ? Optional.of(new User(rs.getLong("id"), rs.getString("name")))
                            : Optional.empty(),
            id);
    }
}
```

Register the implementation by placing a file in `src/main/resources/db/registry/`. The
filename is the fully-qualified name of the **API interface**; the file's single line
of content is the fully-qualified name of the **implementation class**:

```
# file: db/registry/com.example.myplugin.UserRepository
com.example.myplugin.UserRepositoryImpl
```

### 6. Bootstrap the registry

```java
RepositoryRegistry registry = new RepositoryRegistry();

// Scanning phase — call once per plugin/module. Scans the given classloader for
// db/registry/ and db/migrate/ resources.
registry.register(platform, MyPlugin.class.getClassLoader())
    .onConfigure(ctx -> {
        // Bind an API to a SqlDatabaseManager that owns its connection + migrations.
        ctx.publish(UserRepository.class, myDatabaseManager);
    })
    .onReady(reg -> {
        UserRepository users = reg.get(UserRepository.class);
        // use repos... pass registry to consumers... etc...
    });

// Ready phase — resolves conflicts and runs onReady callbacks.
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
client.executeUpsert(UPSERT, userId, score, Instant.now());

// Batch upsert inside a transaction
client.executeTransaction(ctx -> {
    ctx.executeBatch(client.sql(UPSERT), rows);
    return null;
});
```

## License

Apache 2.0 — see [LICENSE](LICENSE).

# Graph Report - dbm-eternal  (2026-04-26)

## Corpus Check
- 75 files · ~127,580 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 871 nodes · 2269 edges · 33 communities detected
- Extraction: 45% EXTRACTED · 55% INFERRED · 0% AMBIGUOUS · INFERRED: 1238 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Community 0|Community 0]]
- [[_COMMUNITY_Community 1|Community 1]]
- [[_COMMUNITY_Community 2|Community 2]]
- [[_COMMUNITY_Community 3|Community 3]]
- [[_COMMUNITY_Community 4|Community 4]]
- [[_COMMUNITY_Community 5|Community 5]]
- [[_COMMUNITY_Community 6|Community 6]]
- [[_COMMUNITY_Community 7|Community 7]]
- [[_COMMUNITY_Community 8|Community 8]]
- [[_COMMUNITY_Community 9|Community 9]]
- [[_COMMUNITY_Community 10|Community 10]]
- [[_COMMUNITY_Community 11|Community 11]]
- [[_COMMUNITY_Community 12|Community 12]]
- [[_COMMUNITY_Community 13|Community 13]]
- [[_COMMUNITY_Community 14|Community 14]]
- [[_COMMUNITY_Community 15|Community 15]]
- [[_COMMUNITY_Community 16|Community 16]]
- [[_COMMUNITY_Community 17|Community 17]]
- [[_COMMUNITY_Community 18|Community 18]]
- [[_COMMUNITY_Community 19|Community 19]]
- [[_COMMUNITY_Community 20|Community 20]]
- [[_COMMUNITY_Community 21|Community 21]]
- [[_COMMUNITY_Community 22|Community 22]]
- [[_COMMUNITY_Community 23|Community 23]]
- [[_COMMUNITY_Community 24|Community 24]]
- [[_COMMUNITY_Community 25|Community 25]]
- [[_COMMUNITY_Community 26|Community 26]]
- [[_COMMUNITY_Community 27|Community 27]]
- [[_COMMUNITY_Community 28|Community 28]]
- [[_COMMUNITY_Community 29|Community 29]]
- [[_COMMUNITY_Community 30|Community 30]]
- [[_COMMUNITY_Community 38|Community 38]]
- [[_COMMUNITY_Community 39|Community 39]]

## God Nodes (most connected - your core abstractions)
1. `of()` - 45 edges
2. `SqlClient` - 35 edges
3. `OneShotConsumableSubscribableEventTest` - 33 edges
4. `executeBatch()` - 32 edges
5. `RepositoryRegistry` - 29 edges
6. `RepositoryRegistryTest` - 25 edges
7. `commit()` - 23 edges
8. `rollback()` - 22 edges
9. `executeChunkedBatch()` - 22 edges
10. `ExecutorLimiter` - 21 edges

## Surprising Connections (you probably didn't know these)
- `RepositoryContractTest` --semantically_similar_to--> `RepositoryRegistryTest`  [INFERRED] [semantically similar]
  dbm-core\src\test\java\io\github\ensgijs\dbm\repository\RepositoryContractTest.java → dbm-core\src\test\java\io\github\ensgijs\dbm\repository\RepositoryRegistryTest.java
- `SqlConnectionConfigTest` --references--> `SqlClient Usage Pattern`  [INFERRED]
  dbm-sql\src\test\java\io\github\ensgijs\dbm\sql\SqlConnectionConfigTest.java → README.md
- `SqlDatabaseManager` --semantically_similar_to--> `SqlClient`  [INFERRED] [semantically similar]
  dbm-core\src\main\java\io\github\ensgijs\dbm\sql\SqlDatabaseManager.java → dbm-sql\src\main\java\io\github\ensgijs\dbm\sql\SqlClient.java
- `ChunkedBatchExecutionException` --semantically_similar_to--> `DatabaseException`  [INFERRED] [semantically similar]
  dbm-sql\src\main\java\io\github\ensgijs\dbm\sql\ChunkedBatchExecutionException.java → dbm-sql\src\main\java\io\github\ensgijs\dbm\sql\DatabaseException.java
- `ThrowingBiFunction` --semantically_similar_to--> `ThrowingFunction`  [INFERRED] [semantically similar]
  dbm-sql\src\main\java\io\github\ensgijs\dbm\util\function\ThrowingBiFunction.java → dbm-sql\src\main\java\io\github\ensgijs\dbm\util\function\ThrowingFunction.java

## Hyperedges (group relationships)
- **Migration Discovery and Execution Pipeline** — migration_MigrationLoader, migration_SchemaMigrator, migration_Migration [EXTRACTED 1.00]
- **Repository Bootstrap Lifecycle** — repository_RepositoryRegistry, repository_RepositoryRegistry_RegistrationHelper, repository_RepositoryRegistry_RegistrationBootstrappingContext [EXTRACTED 1.00]
- **Repository Conflict Resolution System** — repository_RepositoryRegistry_ConflictMode, repository_RepositoryRegistry_RegistrationOptions, repository_RepositoryRegistry [EXTRACTED 0.95]
- **Migration Topological Sort with Dependency Validation** — migrationsortest_migrationsortest, migration_migration, databaseexception_databaseexception [EXTRACTED 1.00]
- **Repository Registry Publish-Bind-Close Lifecycle** — repositoryregistrytest_repositoryregistrytest, repositoryregistry_repositoryregistry, conflictmode_conflictmode, registrationoptions_registrationoptions [EXTRACTED 0.95]
- **SqlClient Session/Transaction/Batch Execution Pipeline** — sqlclient_sqlclient, executioncontext_executioncontext, chunkedbatchexecutionexception_chunkedbatchexecutionexception [EXTRACTED 1.00]
- **Throwing Functional Interfaces Family** — throwingfunction_ThrowingFunction, throwingconsumer_ThrowingConsumer, throwingsupplier_ThrowingSupplier, throwingbiconsumer_ThrowingBiConsumer, throwingbifunction_ThrowingBiFunction [INFERRED 0.95]
- **Subscribable Event Hierarchy** — subscribableevent_SubscribableEvent, consumablesubscribableevent_ConsumableSubscribableEvent, oneshotconsumablesubscribableevent_OneShotConsumableSubscribableEvent [EXTRACTED 1.00]
- **SQL Dialect-Driven Components** — sqldialect_SqlDialect, sqliteconnectionconfig_SqliteConnectionConfig, upsertstatement_UpsertStatement [EXTRACTED 1.00]
- **Threading Test Infrastructure (AsyncVerifier, BlockingTestRunnable, TestRunnable)** — asyncverifier_asyncverifier, blockingtestrunnable_blockingtestrunnable, testrunnable_testrunnable [EXTRACTED 0.95]
- **Concurrency Limiting (ExecutorLimiter, LimitedVirtualThreadPerTaskExecutor, LimiterRunnableWrapper)** — executorlimiter_executorlimiter, limitedvirtualthreadpertaskexecutor_limitedvirtualthreadpertaskexecutor, executorlimiter_limiterrunnablewrapper [EXTRACTED 1.00]
- **Functional Exception Handling (ValueOrException, RetrievalException, ExceptionalSupplier)** — valueorexception_valueorexception, valueorexception_retrievalexception, valueorexceptiontest_valueorexceptiontest [INFERRED 0.85]

## Communities

### Community 0 - "Community 0"
Cohesion: 0.04
Nodes (27): FakeProgrammaticMigration, LimitedVirtualThreadPerTaskExecutor, RegistryEndToEndTest, SqliteOnlyFakeRepositoryImpl, MigrationProvider, SchemaMigrator, MigrateTests, SqlClient (+19 more)

### Community 1 - "Community 1"
Cohesion: 0.07
Nodes (12): OnGlobalRegistryCreatedEventHolder, RegistrationBootstrappingContext, RegistrationHelper, RegistrationOptions, RepositoryRegistry, ResourceWalker, BindingConflictTests, DialectFilterOptionsTests (+4 more)

### Community 2 - "Community 2"
Cohesion: 0.07
Nodes (8): ConsumableSubscribableEventTest, OneShotConsumableSubscribableEvent, OneShotConsumableSubscribableEventTest, SqlStatementSplitter, StatementSplitException, SqlStatementSplitterTest, SubscribableEvent, ThrowingSupplier

### Community 3 - "Community 3"
Cohesion: 0.05
Nodes (16): MigrationSource, ProgrammaticMigration, sourceType(), toString(), version(), MigrationLoader, of(), LoadedResourcesTests (+8 more)

### Community 4 - "Community 4"
Cohesion: 0.07
Nodes (9): AsyncVerifier, TestFailure, MySqlTests, SqlConnectionConfigTest, SqliteTests, SqlDatabaseManager, isEquivalent(), ValueOrException (+1 more)

### Community 5 - "Community 5"
Cohesion: 0.06
Nodes (6): BlockingTestRunnable, ExecutorLimiter, LimiterRunnableWrapper, ExecutorLimiterTest, OneShotCondition, TestRunnable

### Community 6 - "Community 6"
Cohesion: 0.11
Nodes (14): ChunkedBatchExecutionException, commit(), executeBatch(), executeChunkedBatch(), executeQuery(), executeUpdate(), prepare(), rollback() (+6 more)

### Community 7 - "Community 7"
Cohesion: 0.05
Nodes (24): AbstractBaseLogic, AbstractMidLevel, AltFakeRepoImpl, BaseLogic, BaseLogicImpl, CircularDependencyTests, DeepChainImpl, DirectLogic (+16 more)

### Community 8 - "Community 8"
Cohesion: 0.06
Nodes (18): Repository, AnnotatedConcreteClass, ChildApi, ChildImpl, DualApiImpl, IdentifyRepositoryApiTest, IndirectApi, ItemRepo (+10 more)

### Community 9 - "Community 9"
Cohesion: 0.15
Nodes (5): sort(), ParsedMigrationFileNameTests, MigrationSortTest, CollectMigrationNamesTest, of()

### Community 10 - "Community 10"
Cohesion: 0.12
Nodes (10): configurePool(), connectionId(), getDbUrl(), maxConnections(), configurePool(), connectionId(), databaseName(), getDbUrl() (+2 more)

### Community 11 - "Community 11"
Cohesion: 0.11
Nodes (12): dbm-core Module, dbm-eternal Project README, dbm-sql Module, Migration File Naming Convention, Multi-Platform Design Intent, Repository Pattern, SqlClient Usage Pattern, UpsertStatement Usage (+4 more)

### Community 12 - "Community 12"
Cohesion: 0.11
Nodes (3): DatabaseException, DatabaseExceptionTest, RetrievalException

### Community 13 - "Community 13"
Cohesion: 0.16
Nodes (6): DuplicateDialect, MultiDialect, NoAnnotation, RepositoryImplTest, SqliteOnly, WithUndefined

### Community 14 - "Community 14"
Cohesion: 0.23
Nodes (4): RepositoryRegistryScanTest, asReader(), asStream(), ResourceScanner

### Community 15 - "Community 15"
Cohesion: 0.18
Nodes (5): BaseRepo, EmptyAnnotRepo, MissingAnnotRepo, SchemaMigratorTest, SqlDatabaseManagerTest

### Community 16 - "Community 16"
Cohesion: 0.31
Nodes (1): ConsumableSubscribableEvent

### Community 17 - "Community 17"
Cohesion: 0.25
Nodes (1): SqlConnectionConfig

### Community 18 - "Community 18"
Cohesion: 0.32
Nodes (2): ThrowingBiConsumer, ThrowingConsumer

### Community 19 - "Community 19"
Cohesion: 0.29
Nodes (1): FakeRepositoryImpl

### Community 20 - "Community 20"
Cohesion: 0.33
Nodes (2): configurePool(), getDbUrl()

### Community 21 - "Community 21"
Cohesion: 0.4
Nodes (2): ExceptionalFunction, ThrowingBiFunction

### Community 22 - "Community 22"
Cohesion: 0.33
Nodes (1): ObjectHelpers

### Community 23 - "Community 23"
Cohesion: 0.4
Nodes (1): AbstractRepository

### Community 24 - "Community 24"
Cohesion: 0.4
Nodes (1): FakeRepository

### Community 25 - "Community 25"
Cohesion: 0.5
Nodes (1): BubbleUpException

### Community 26 - "Community 26"
Cohesion: 0.67
Nodes (1): MigrationParseException

### Community 27 - "Community 27"
Cohesion: 0.67
Nodes (1): AmbiguousRepositoryApiException

### Community 28 - "Community 28"
Cohesion: 0.67
Nodes (1): RepositoryInitializationException

### Community 29 - "Community 29"
Cohesion: 0.67
Nodes (1): RepositoryNotRegisteredException

### Community 30 - "Community 30"
Cohesion: 0.67
Nodes (1): ExceptionalSupplier

### Community 38 - "Community 38"
Cohesion: 1.0
Nodes (1): migration package-info

### Community 39 - "Community 39"
Cohesion: 1.0
Nodes (1): repository package-info

## Knowledge Gaps
- **34 isolated node(s):** `OnGlobalRegistryCreatedEventHolder`, `BaseRepo`, `MissingAnnotRepo`, `EmptyAnnotRepo`, `ItemRepo` (+29 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Community 16`** (9 nodes): `ConsumableSubscribableEvent`, `.accept()`, `.ConsumableSubscribableEvent()`, `.hasSubscribers()`, `.subscribe()`, `.toString()`, `.unsubscribe()`, `.update()`, `ConsumableSubscribableEvent.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 17`** (8 nodes): `SqlConnectionConfig.java`, `SqlConnectionConfig`, `.configurePool()`, `.connectionId()`, `.dialect()`, `.getDbUrl()`, `.isEquivalent()`, `.maxConnections()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 18`** (8 nodes): `ThrowingBiConsumer.java`, `ThrowingConsumer.java`, `ThrowingBiConsumer`, `.accept()`, `.andThen()`, `ThrowingConsumer`, `.accept()`, `.andThen()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 19`** (7 nodes): `FakeRepositoryImpl.java`, `FakeRepositoryImpl`, `.clear()`, `.FakeRepositoryImpl()`, `.get()`, `.invalidateCaches()`, `.put()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 20`** (7 nodes): `MySqlConnectionConfig.java`, `configurePool()`, `connectionId()`, `dialect()`, `getDbUrl()`, `isEquivalent()`, `toString()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 21`** (6 nodes): `ExceptionalFunction.java`, `ThrowingBiFunction.java`, `ExceptionalFunction`, `.apply()`, `ThrowingBiFunction`, `.apply()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 22`** (6 nodes): `ObjectHelpers.java`, `ObjectHelpers`, `.as()`, `.asEnum()`, `.coalesce()`, `.ObjectHelpers()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 23`** (5 nodes): `AbstractRepository`, `.AbstractRepository()`, `.invalidateCaches()`, `.onCacheInvalidatedEvent()`, `AbstractRepository.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 24`** (5 nodes): `FakeRepository.java`, `FakeRepository`, `.clear()`, `.get()`, `.put()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 25`** (4 nodes): `BubbleUpException`, `.BubbleUpException()`, `.unwrap()`, `BubbleUpException.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 26`** (3 nodes): `MigrationParseException.java`, `MigrationParseException`, `.MigrationParseException()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 27`** (3 nodes): `AmbiguousRepositoryApiException`, `.AmbiguousRepositoryApiException()`, `AmbiguousRepositoryApiException.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 28`** (3 nodes): `RepositoryInitializationException.java`, `RepositoryInitializationException`, `.RepositoryInitializationException()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 29`** (3 nodes): `RepositoryNotRegisteredException.java`, `RepositoryNotRegisteredException`, `.RepositoryNotRegisteredException()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 30`** (3 nodes): `ExceptionalSupplier.java`, `ExceptionalSupplier`, `.get()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 38`** (1 nodes): `migration package-info`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 39`** (1 nodes): `repository package-info`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `of()` connect `Community 9` to `Community 0`, `Community 1`, `Community 2`, `Community 3`, `Community 6`, `Community 13`, `Community 14`?**
  _High betweenness centrality (0.053) - this node is a cross-community bridge._
- **Why does `RepositoryRegistryTest` connect `Community 1` to `Community 8`, `Community 10`, `Community 7`?**
  _High betweenness centrality (0.047) - this node is a cross-community bridge._
- **Why does `SqlClient` connect `Community 0` to `Community 10`, `Community 3`, `Community 4`, `Community 5`?**
  _High betweenness centrality (0.032) - this node is a cross-community bridge._
- **Are the 44 inferred relationships involving `of()` (e.g. with `.find()` and `.testFileNameParsingSql_HappyCase_MySql_ForwardSlashes()`) actually correct?**
  _`of()` has 44 INFERRED edges - model-reasoned connections that need verification._
- **Are the 29 inferred relationships involving `executeBatch()` (e.g. with `.getConnection()` and `commit()`) actually correct?**
  _`executeBatch()` has 29 INFERRED edges - model-reasoned connections that need verification._
- **What connects `OnGlobalRegistryCreatedEventHolder`, `BaseRepo`, `MissingAnnotRepo` to the rest of the system?**
  _34 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.04 - nodes in this community are weakly interconnected._
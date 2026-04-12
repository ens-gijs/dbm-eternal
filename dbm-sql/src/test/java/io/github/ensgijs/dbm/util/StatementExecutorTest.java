package io.github.ensgijs.dbm.util;

import io.github.ensgijs.dbm.util.function.ThrowingBiConsumer;
import io.github.ensgijs.dbm.sql.ChunkedBatchExecutionException;
import io.github.ensgijs.dbm.sql.DatabaseException;
import io.github.ensgijs.dbm.sql.StatementExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StatementExecutorTest {

    private PreparedStatement mockPs;
    private Connection mockConn;
    private StatementExecutor executor; // The class containing your executeBatch method

    @BeforeEach
    void setUp() throws SQLException {
        mockPs = mock(PreparedStatement.class);
        mockConn = mock(Connection.class);
        ParameterMetaData mockMetaData = mock(ParameterMetaData.class);

        // Standard mock behavior
        when(mockPs.getConnection()).thenReturn(mockConn);
        when(mockPs.getParameterMetaData()).thenReturn(mockMetaData);
        when(mockMetaData.getParameterCount()).thenReturn(2);

        executor = spy(new StatementExecutor(mockPs));
    }

    @Test
    @DisplayName("setParams should map UUID to String and others to Object")
    void testSetParams() throws SQLException {
        UUID uuid = UUID.randomUUID();
        Object[] params = {"string", 123, uuid};

        StatementExecutor.setParams(mockPs, params);

        verify(mockPs).setObject(1, "string");
        verify(mockPs).setObject(2, 123);
        verify(mockPs).setString(3, uuid.toString());
        verify(mockPs, never()).setObject(eq(3), any());
    }

    @Test
    @DisplayName("executeUpdate should map params and return count")
    void testExecuteUpdate() throws SQLException {
        when(mockPs.executeUpdate()).thenReturn(5);

        int result = executor.executeUpdate("param1");

        assertEquals(5, result);
        verify(mockPs).setObject(1, "param1");
        verify(mockPs).executeUpdate();
    }

    @Test
    @DisplayName("executeQuery should map params and return count")
    void testExecuteQuery() throws SQLException {
        ResultSet mockRs = mock(ResultSet.class);
        // Simulate one existing migration: "users" version 1
        when(mockRs.next()).thenReturn(true, false);
        when(mockRs.getString(1)).thenReturn("users");
        when(mockRs.getLong(2)).thenReturn(1L);
        when(mockPs.executeQuery()).thenReturn(mockRs);

        executor.executeQuery(rs -> {
            assertSame(mockRs, rs);
            return null;
        });
    }


    @Test
    @DisplayName("close should delegate to PreparedStatement")
    void testClose() throws SQLException {
        executor.close();
        verify(mockPs).close();
    }

    @Nested
    class ExecuteBatchObjectMappingTest {
        @Test
        @DisplayName("Should return empty array when batchObjects is null or empty")
        void testEmptyInput() throws DatabaseException {
            int[] result = executor.executeBatch(null, (obj, args) -> {});
            assertEquals(0, result.length);

            result = executor.executeBatch(Collections.emptyList(), (obj, args) -> {});
            assertEquals(0, result.length);

            verifyNoInteractions(mockConn);
        }

        @Test
        @DisplayName("Session Mode: Should commit and restore autoCommit when initial state is true")
        void testSessionModeSuccess() throws SQLException, DatabaseException {
            // Given
            when(mockConn.getAutoCommit()).thenReturn(true);
            int[] expectedResult = {1, 1};
            when(mockPs.executeBatch()).thenReturn(expectedResult);

            // When
            int[] result = executor.executeBatch(Arrays.asList("A", "B"), (str, args) -> args[0] = str);

            // Then
            verify(mockConn).setAutoCommit(false); // Turned off for batch
            InOrder order = inOrder(mockPs);
            order.verify(mockPs).setObject(1, "A");
            order.verify(mockPs).addBatch();
            order.verify(mockPs).setObject(1, "B");
            order.verify(mockPs).addBatch();
            order.verify(mockPs).executeBatch();
            order.verifyNoMoreInteractions();
            verify(mockConn).commit();             // Committed because we are in Session Mode
            verify(mockConn).setAutoCommit(true);  // Restored to original
            assertArrayEquals(expectedResult, result);
        }

        @Test
        @DisplayName("Session Mode: Should rollback on failure")
        void testSessionModeRollback() throws SQLException {
            // Given
            when(mockConn.getAutoCommit()).thenReturn(true);
            when(mockPs.executeBatch()).thenThrow(new SQLException("DB Error"));

            // When/Then
            assertThrows(DatabaseException.class, () ->
                    executor.executeBatch(List.of("A"), (s, a) -> {})
            );

            verify(mockConn).rollback();
            verify(mockConn).setAutoCommit(true); // Ensure cleanup in finally block
        }

        @Test
        @DisplayName("Transaction Mode: Should NOT commit or rollback when initial AutoCommit state is false")
        void testTransactionMode() throws SQLException, DatabaseException {
            // Given
            when(mockConn.getAutoCommit()).thenReturn(false);
            int[] expectedResult = {1};
            when(mockPs.executeBatch()).thenReturn(expectedResult);

            // When
            int[] result = executor.executeBatch(List.of("A"), (s, a) -> {});

            // Then
            verify(mockPs).executeBatch();
            verify(mockConn, never()).commit();
            verify(mockConn, never()).rollback();
            verify(mockConn, never()).setAutoCommit(anyBoolean());
            assertArrayEquals(expectedResult, result);
        }

        @Test
        @DisplayName("Should clear arguments array between batch items")
        void testArgumentClearing() throws Exception {
            // Given
            when(mockConn.getAutoCommit()).thenReturn(false);
            List<String> data = Arrays.asList("First", "Second");

            // When
            executor.executeBatch(data, (str, args) -> {
                assertNull(args[0], "Args should be cleared before mapping");
                args[0] = str;
            });

            InOrder order = inOrder(mockPs);
            order.verify(mockPs).setObject(1, "First");
            order.verify(mockPs).addBatch();
            order.verify(mockPs).setObject(1, "Second");
            order.verify(mockPs).addBatch();
            order.verify(mockPs).executeBatch();
            order.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("Rollback Branch: Should NOT rollback if initialization fails (safe is false)")
        void testNoRollbackIfSafeIsFalse() throws SQLException {
            // Given: ps.getConnection() throws an exception
            // This happens before 'safe = true' is reached
            when(mockPs.getConnection()).thenThrow(new SQLException("Connection failed"));

            // When/Then
            assertThrows(DatabaseException.class, () ->
                    executor.executeBatch(List.of("A"), (s, a) -> {})
            );

            // Verify: rollback is never called because 'safe' remained false
            verify(mockConn, never()).rollback();
            // Verify: we didn't even try to set autoCommit back because safe is false
            verify(mockConn, never()).setAutoCommit(anyBoolean());
        }

        @Test
        @DisplayName("Rollback Branch: Should NOT rollback on failure if in Transaction Mode (originalAutoCommit is false)")
        void testNoRollbackInTransactionMode() throws SQLException {
            // Given: Connection is already in a transaction
            when(mockConn.getAutoCommit()).thenReturn(false);

            // Simulate a failure during the batch execution
            when(mockPs.executeBatch()).thenThrow(new SQLException("Execution failed"));

            // When/Then
            assertThrows(DatabaseException.class, () ->
                    executor.executeBatch(List.of("A"), (s, a) -> {})
            );

            // Verify: safe was true, but originalAutoCommit was false
            // Therefore, rollback() must NOT be called
            verify(mockConn, never()).rollback();

            // Ensure we still restored the state (which was false)
            verify(mockConn, never()).setAutoCommit(anyBoolean());
        }
    }


    @Nested
    class ExecuteBatchObjectArraysParamTest {
        final List<Object[]> data = Arrays.asList(new Object[]{"A"}, new Object[]{"B"});

        @Test
        @DisplayName("Should return empty array when batchObjects is null or empty")
        void testEmptyInput() throws DatabaseException {
            int[] result = executor.executeBatch(null);
            assertEquals(0, result.length);

            result = executor.executeBatch(Collections.emptyList());
            assertEquals(0, result.length);

            verifyNoInteractions(mockConn);
        }

        @Test
        @DisplayName("Session Mode: Should commit and restore autoCommit when initial state is true")
        void testSessionModeSuccess() throws SQLException, DatabaseException {
            // Given
            when(mockConn.getAutoCommit()).thenReturn(true);
            int[] expectedResult = {1, 1};
            when(mockPs.executeBatch()).thenReturn(expectedResult);

            // When
            int[] result = executor.executeBatch(data);

            // Then
            verify(mockConn).setAutoCommit(false); // Turned off for batch
            InOrder order = inOrder(mockPs);
            order.verify(mockPs).setObject(1, "A");
            order.verify(mockPs).addBatch();
            order.verify(mockPs).setObject(1, "B");
            order.verify(mockPs).addBatch();
            order.verify(mockPs).executeBatch();
            order.verifyNoMoreInteractions();
            verify(mockConn).commit();             // Committed because we are in Session Mode
            verify(mockConn).setAutoCommit(true);  // Restored to original
            assertArrayEquals(expectedResult, result);
        }

        @Test
        @DisplayName("Session Mode: Should rollback on failure")
        void testSessionModeRollback() throws SQLException {
            // Given
            when(mockConn.getAutoCommit()).thenReturn(true);
            when(mockPs.executeBatch()).thenThrow(new SQLException("DB Error"));

            // When/Then
            assertThrows(DatabaseException.class, () ->
                    executor.executeBatch(data)
            );

            verify(mockConn).rollback();
            verify(mockConn).setAutoCommit(true); // Ensure cleanup in finally block
        }

        @Test
        @DisplayName("Transaction Mode: Should NOT commit or rollback when initial AutoCommit state is false")
        void testTransactionMode() throws SQLException, DatabaseException {
            // Given
            when(mockConn.getAutoCommit()).thenReturn(false);
            int[] expectedResult = {1, 1};
            when(mockPs.executeBatch()).thenReturn(expectedResult);

            // When
            int[] result = executor.executeBatch(data);

            // Then
            verify(mockPs).executeBatch();
            verify(mockConn, never()).commit();
            verify(mockConn, never()).rollback();
            verify(mockConn, never()).setAutoCommit(anyBoolean());
            assertArrayEquals(expectedResult, result);
        }

        @Test
        @DisplayName("Rollback Branch: Should NOT rollback if initialization fails (safe is false)")
        void testNoRollbackIfSafeIsFalse() throws SQLException {
            // Given: ps.getConnection() throws an exception
            // This happens before 'safe = true' is reached
            when(mockPs.getConnection()).thenThrow(new SQLException("Connection failed"));

            // When/Then
            assertThrows(DatabaseException.class, () ->
                    executor.executeBatch(data)
            );

            // Verify: rollback is never called because 'safe' remained false
            verify(mockConn, never()).rollback();
            // Verify: we didn't even try to set autoCommit back because safe is false
            verify(mockConn, never()).setAutoCommit(anyBoolean());
        }

        @Test
        @DisplayName("Rollback Branch: Should NOT rollback on failure if in Transaction Mode (originalAutoCommit is false)")
        void testNoRollbackInTransactionMode() throws SQLException {
            // Given: Connection is already in a transaction
            when(mockConn.getAutoCommit()).thenReturn(false);

            // Simulate a failure during the batch execution
            when(mockPs.executeBatch()).thenThrow(new SQLException("Execution failed"));

            // When/Then
            assertThrows(DatabaseException.class, () ->
                    executor.executeBatch(data)
            );

            // Verify: safe was true, but originalAutoCommit was false
            // Therefore, rollback() must NOT be called
            verify(mockConn, never()).rollback();

            // Ensure we still restored the state (which was false)
            verify(mockConn, never()).setAutoCommit(anyBoolean());
        }
    }


    @Nested
    class ExecuteChunkedBatchObjectMappingTest {

        private List<Integer> createBatch(int size) {
            List<Integer> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) list.add(i);
            return list;
        }

        private void mapper(Integer v, Object[] params) {
            params[0] = "v" + v;
        }

        @Test
        @DisplayName("Should return empty array when batchObjects is null or empty")
        void testEmptyInput() throws DatabaseException {
            int[] result = executor.executeChunkedBatch(2, null, this::mapper);
            assertEquals(0, result.length);

            result = executor.executeChunkedBatch(2, Collections.emptyList(), this::mapper);
            assertEquals(0, result.length);

            verifyNoInteractions(mockConn);
        }

        @Test
        @DisplayName("Should delegate to executeBatch if size <= maxChunkingSize")
        void testDelegation() throws DatabaseException {
            List<Integer> batch = createBatch(2);

            ThrowingBiConsumer<Integer, Object[]> m = this::mapper;
            executor.executeChunkedBatch(10, batch, m);

            // Verify it routed to the single-batch method
            verify(executor).executeBatch(same(batch), same(m));
        }

        @Test
        @DisplayName("Safe Branch: Should not interact with connection if initialization fails")
        void testSafeFlagWithException() throws SQLException {
            // Simulate failure during conn.getAutoCommit()
            when(mockPs.getConnection()).thenReturn(mockConn);
            when(mockConn.getAutoCommit()).thenThrow(new SQLException("Network failure"));

            assertThrows(ChunkedBatchExecutionException.class, () ->
                    executor.executeChunkedBatch(2, createBatch(4), this::mapper)
            );

            // Verify "safe" was false, so no cleanup/rollback attempted
            verify(mockPs, never()).executeBatch();
            verify(mockConn, never()).rollback();
            verify(mockConn, never()).setAutoCommit(anyBoolean());
        }


        @Test
        @DisplayName("Session Mode: Should commit after every chunk and restore state")
        void testSessionModeMultiCommit() throws SQLException, DatabaseException {
            // Setup: 5 items, max size 2 -> 3 chunks (2, 2, 1)
            when(mockConn.getAutoCommit()).thenReturn(true);
            when(mockPs.executeBatch()).thenReturn(new int[]{1, 1}, new int[]{1, 1}, new int[]{1});

            int[] results = executor.executeChunkedBatch(2, createBatch(5), this::mapper);

            assertEquals(5, results.length);
            verify(mockConn, times(3)).commit(); // Verify commit per chunk
            verify(mockConn).setAutoCommit(false); // Initial disable
            verify(mockConn).setAutoCommit(true);  // Final restore
        }

        @Test
        @DisplayName("Session Mode: Partial Failure tracks progress correctly")
        void testSessionModePartialFailure() throws SQLException {
            // Given: 4 items, chunk size 2. First chunk succeeds, second fails.
            when(mockConn.getAutoCommit()).thenReturn(true);

            // First call returns success, second throws
            when(mockPs.executeBatch())
                    .thenReturn(new int[]{1, 1})
                    .thenThrow(new SQLException("Chunk 2 Failed"));

            // When/Then
            ChunkedBatchExecutionException ex = assertThrows(ChunkedBatchExecutionException.class, () ->
                    executor.executeChunkedBatch(2, createBatch(4), this::mapper)
            );

            assertEquals(2, ex.commitedCount()); // First chunk (size 2) was committed
            assertEquals(2, ex.failedChunkStart()); // Failure started at index 2
            assertArrayEquals(new int[]{1, 1, 0, 0}, ex.updateCounts());
            verify(mockConn, times(1)).commit();   // Committed once
            verify(mockConn, times(1)).rollback(); // Rolled back once (for chunk 2)
            verify(mockConn).setAutoCommit(true);  // Still restores state
        }


        @Test
        @DisplayName("Transaction Mode success: No commits even with multiple chunks or alter autoCommit")
        void testTransactionModeSuccessMakesNoCommits() throws SQLException, DatabaseException {
            // Given: 4 items, chunk size 2
            when(mockConn.getAutoCommit()).thenReturn(false);
            when(mockPs.executeBatch()).thenReturn(new int[]{1, 1});

            // When
            int[] result = executor.executeChunkedBatch(2, createBatch(4), this::mapper);

            // Then
            assertArrayEquals(new int[]{1, 1, 1, 1}, result);
            verify(mockPs, times(2)).executeBatch();
            verify(mockConn, never()).commit();
            verify(mockConn, never()).rollback();
            verify(mockConn, never()).setAutoCommit(anyBoolean());
        }

        @Test
        @DisplayName("Transaction Mode failure: Should NOT commit, rollback, or restore autoCommit")
        void testTransactionModePartialFailure() throws SQLException, DatabaseException {
            // Given: Already in a transaction
            when(mockConn.getAutoCommit()).thenReturn(false);
            when(mockPs.executeBatch())
                    .thenReturn(new int[]{1, 1})
                    .thenThrow(new SQLException("Chunk 2 Failed"));

            ChunkedBatchExecutionException ex = assertThrows(ChunkedBatchExecutionException.class, () ->
                    executor.executeChunkedBatch(2, createBatch(4), this::mapper));

            assertArrayEquals(new int[]{1, 1, 0, 0}, ex.updateCounts());
            assertEquals(0, ex.commitedCount()); // First chunk (size 2) was NOT committed
            assertEquals(2, ex.failedChunkStart()); // Failure started at index 2
            verify(mockPs, times(2)).executeBatch();
            verify(mockConn, never()).commit();
            verify(mockConn, never()).rollback();
            verify(mockConn, never()).setAutoCommit(anyBoolean()); // Should NOT set to true if it was false
        }
    }

    @Nested
    class ExecuteChunkedBatchObjectArraysParamTest {

        private List<Object[]> createBatch(int size) {
            List<Object[]> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) list.add(new Object[]{"" + i});
            return list;
        }

        @Test
        @DisplayName("Should return empty array when batchObjects is null or empty")
        void testEmptyInput() throws DatabaseException {
            int[] result = executor.executeChunkedBatch(2, null);
            assertEquals(0, result.length);

            result = executor.executeChunkedBatch(2, Collections.emptyList());
            assertEquals(0, result.length);

            verifyNoInteractions(mockConn);
        }

        @Test
        @DisplayName("Should delegate to executeBatch if size <= maxChunkingSize")
        void testDelegation() throws DatabaseException {
            List<Object[]> batch = List.of(new Object[]{"1"}, new Object[]{"2"});

            executor.executeChunkedBatch(10, batch);

            // Verify it routed to the single-batch method
            verify(executor).executeBatch(eq(batch));
        }

        @Test
        @DisplayName("Safe Branch: Should not interact with connection if initialization fails")
        void testSafeFlagWithException() throws SQLException {
            // Simulate failure during conn.getAutoCommit()
            when(mockPs.getConnection()).thenReturn(mockConn);
            when(mockConn.getAutoCommit()).thenThrow(new SQLException("Network failure"));

            assertThrows(ChunkedBatchExecutionException.class, () ->
                    executor.executeChunkedBatch(2, createBatch(4))
            );

            // Verify "safe" was false, so no cleanup/rollback attempted
            verify(mockConn, never()).rollback();
            verify(mockConn, never()).setAutoCommit(anyBoolean());
        }


        @Test
        @DisplayName("Session Mode: Should commit after every chunk and restore state")
        void testSessionModeMultiCommit() throws SQLException, DatabaseException {
            // Setup: 5 items, max size 2 -> 3 chunks (2, 2, 1)
            when(mockConn.getAutoCommit()).thenReturn(true);
            List<Object[]> batch = createBatch(5);
            when(mockPs.executeBatch()).thenReturn(new int[]{1, 1}, new int[]{1, 1}, new int[]{1});

            int[] results = executor.executeChunkedBatch(2, batch);

            assertEquals(5, results.length);
            verify(mockConn, times(3)).commit(); // Verify commit per chunk
            verify(mockConn).setAutoCommit(false); // Initial disable
            verify(mockConn).setAutoCommit(true);  // Final restore
        }

        @Test
        @DisplayName("Session Mode: Partial Failure tracks progress correctly")
        void testSessionModePartialFailure() throws SQLException {
            // Given: 4 items, chunk size 2. First chunk succeeds, second fails.
            when(mockConn.getAutoCommit()).thenReturn(true);
            List<Object[]> batch = createBatch(4);

            // First call returns success, second throws
            when(mockPs.executeBatch())
                    .thenReturn(new int[]{1, 1})
                    .thenThrow(new SQLException("Chunk 2 Failed"));

            // When/Then
            ChunkedBatchExecutionException ex = assertThrows(ChunkedBatchExecutionException.class, () ->
                    executor.executeChunkedBatch(2, batch)
            );

            assertEquals(2, ex.commitedCount()); // First chunk (size 2) was committed
            assertEquals(2, ex.failedChunkStart()); // Failure started at index 2
            assertArrayEquals(new int[]{1, 1, 0, 0}, ex.updateCounts());
            verify(mockConn, times(1)).commit();   // Committed once
            verify(mockConn, times(1)).rollback(); // Rolled back once (for chunk 2)
            verify(mockConn).setAutoCommit(true);  // Still restores state
        }


        @Test
        @DisplayName("Transaction Mode success: No commits even with multiple chunks or alter autoCommit")
        void testTransactionModeSuccessMakesNoCommits() throws SQLException, DatabaseException {
            // Given: 4 items, chunk size 2
            when(mockConn.getAutoCommit()).thenReturn(false);
            List<Object[]> batch = createBatch(4);
            when(mockPs.executeBatch()).thenReturn(new int[]{1, 1});

            // When
            int[] result = executor.executeChunkedBatch(2, batch);

            // Then
            assertArrayEquals(new int[]{1, 1, 1, 1}, result);
            verify(mockPs, times(2)).executeBatch();
            verify(mockConn, never()).commit();
            verify(mockConn, never()).rollback();
            verify(mockConn, never()).setAutoCommit(anyBoolean());
        }

        @Test
        @DisplayName("Transaction Mode failure: Should NOT commit, rollback, or restore autoCommit")
        void testTransactionModePartialFailure() throws SQLException, DatabaseException {
            // Given: Already in a transaction
            when(mockConn.getAutoCommit()).thenReturn(false);
            List<Object[]> batch = createBatch(4);
            when(mockPs.executeBatch())
                    .thenReturn(new int[]{1, 1})
                    .thenThrow(new SQLException("Chunk 2 Failed"));

            ChunkedBatchExecutionException ex = assertThrows(ChunkedBatchExecutionException.class, () ->
                    executor.executeChunkedBatch(2, batch));

            assertArrayEquals(new int[]{1, 1, 0, 0}, ex.updateCounts());
            assertEquals(0, ex.commitedCount()); // First chunk (size 2) was NOT committed
            assertEquals(2, ex.failedChunkStart()); // Failure started at index 2
            verify(mockPs, times(2)).executeBatch();
            verify(mockConn, never()).commit();
            verify(mockConn, never()).rollback();
            verify(mockConn, never()).setAutoCommit(anyBoolean()); // Should NOT set to true if it was false
        }
    }
}
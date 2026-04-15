package io.github.ensgijs.dbm.util.objects;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ValueOrExceptionTest {

    // ---- forValue ----

    @Test
    void forValue_hasValueAndNoException() {
        ValueOrException<String, Exception> voe = ValueOrException.forValue("hello");
        assertTrue(voe.hasValue());
        assertFalse(voe.hasException());
        assertEquals("hello", voe.getValue());
        assertNull(voe.getException());
        assertEquals("hello", voe.getOrThrow());
    }

    @Test
    void forValue_nullValueIsAllowed() {
        ValueOrException<String, Exception> voe = ValueOrException.forValue(null);
        assertFalse(voe.hasValue(), "null value means hasValue() should be false");
        assertNull(voe.getValue());
        assertNull(voe.getOrThrow());
    }

    // ---- forException ----

    @Test
    void forException_hasExceptionAndNoValue() {
        IOException ex = new IOException("test error");
        ValueOrException<String, IOException> voe = ValueOrException.forException(ex);
        assertTrue(voe.hasException());
        assertFalse(voe.hasValue());
        assertNull(voe.getValue());
        assertSame(ex, voe.getException());
    }

    @Test
    void forException_getOrThrow_throwsRetrievalException() {
        IOException ex = new IOException("test error");
        ValueOrException<String, IOException> voe = ValueOrException.forException(ex);
        ValueOrException.RetrievalException thrown = assertThrows(
                ValueOrException.RetrievalException.class, voe::getOrThrow);
        assertSame(ex, thrown.getCause());
    }

    @Test
    void forException_getOrThrow_isIdempotent() {
        IOException ex = new IOException("test error");
        ValueOrException<String, IOException> voe = ValueOrException.forException(ex);
        for (int i = 0; i < 3; i++) {
            ValueOrException.RetrievalException thrown = assertThrows(
                    ValueOrException.RetrievalException.class, voe::getOrThrow);
            assertSame(ex, thrown.getCause(), "cause must be the original exception on call " + (i + 1));
        }
    }

    @Test
    void retrievalException_unwrap_castsToCause() {
        IOException ex = new IOException("test error");
        ValueOrException<String, IOException> voe = ValueOrException.forException(ex);
        ValueOrException.RetrievalException thrown = assertThrows(
                ValueOrException.RetrievalException.class, voe::getOrThrow);
        assertSame(ex, thrown.unwrap(IOException.class));
    }

    // ---- getOrThrow(Class) ----

    @Test
    void forValue_getOrThrowClass_returnsValue() throws Exception {
        ValueOrException<String, IOException> voe = ValueOrException.forValue("hello");
        assertEquals("hello", voe.getOrThrow(IOException.class));
    }

    @Test
    void forException_getOrThrowClass_throwsWrapperWithOriginalAsCause() {
        IOException original = new IOException("original");
        ValueOrException<String, IOException> voe = ValueOrException.forException(original);
        IOException thrown = assertThrows(IOException.class, () -> voe.getOrThrow(IOException.class));
        assertNotSame(original, thrown, "should be a new wrapper instance, not the original");
        assertSame(original, thrown.getCause(), "original must be the cause");
    }

    @Test
    void forException_getOrThrowClass_isIdempotent() {
        IOException original = new IOException("original");
        ValueOrException<String, IOException> voe = ValueOrException.forException(original);
        for (int i = 0; i < 3; i++) {
            IOException thrown = assertThrows(IOException.class, () -> voe.getOrThrow(IOException.class));
            assertSame(original, thrown.getCause(), "cause must be the original on call " + (i + 1));
        }
    }

    @Test
    void forException_getOrThrowClass_noSuitableConstructor_throwsIllegalArgumentException() {
        // RuntimeException has (String, Throwable) constructor but let's use a class without one
        class NoGoodCtor extends Exception {
            NoGoodCtor() { super("no-good"); }
        }
        ValueOrException<String, IOException> voe = ValueOrException.forException(new IOException("x"));
        assertThrows(IllegalArgumentException.class, () -> voe.getOrThrow(NoGoodCtor.class));
    }

    // ---- eval ----

    @Test
    void eval_successCapturesToValue() {
        ValueOrException<Integer, Exception> voe = ValueOrException.eval(() -> 42);
        assertFalse(voe.hasException());
        assertEquals(42, voe.getOrThrow());
    }

    @Test
    void eval_exceptionCapturedWithoutThrowing() {
        IOException toThrow = new IOException("boom");
        ValueOrException<String, IOException> voe = ValueOrException.eval(() -> { throw toThrow; });
        assertTrue(voe.hasException());
        assertSame(toThrow, voe.getException());
    }

    @Test
    void eval_withErrorCallback_callsCallbackOnFailure() {
        IOException toThrow = new IOException("boom");
        List<Exception> captured = new ArrayList<>();
        ValueOrException<String, IOException> voe = ValueOrException.eval(() -> { throw toThrow; }, captured::add);
        assertTrue(voe.hasException());
        assertEquals(1, captured.size());
        assertSame(toThrow, captured.get(0));
    }

    @Test
    void eval_withErrorCallback_doesNotCallCallbackOnSuccess() {
        List<Exception> captured = new ArrayList<>();
        ValueOrException<String, Exception> voe = ValueOrException.eval(() -> "ok", captured::add);
        assertEquals("ok", voe.getOrThrow());
        assertTrue(captured.isEmpty(), "Error callback should not fire on success");
    }

    // ---- wrap ----

    @Test
    void wrap_convertsThrowingFunctionToSafeFunction() {
        IOException toThrow = new IOException("wrapped error");
        Function<String, ValueOrException<Integer, IOException>> fn =
                ValueOrException.wrap(s -> { throw toThrow; });

        ValueOrException<Integer, IOException> result = fn.apply("input");
        assertTrue(result.hasException());
        assertSame(toThrow, result.getException());
    }

    @Test
    void wrap_successPath() {
        Function<String, ValueOrException<Integer, Exception>> fn =
                ValueOrException.wrap(String::length);
        ValueOrException<Integer, Exception> result = fn.apply("hello");
        assertFalse(result.hasException());
        assertEquals(5, result.getValue());
    }

    // ---- empty ----

    @Test
    void empty_hasNeitherValueNorException() {
        ValueOrException<String, Exception> empty = ValueOrException.empty();
        assertFalse(empty.hasValue());
        assertFalse(empty.hasException());
        assertNull(empty.getOrThrow());
    }

    // ---- toString ----

    @Test
    void toString_valuePresent_startsWithVALUE() {
        ValueOrException<String, Exception> voe = ValueOrException.forValue("test");
        assertTrue(voe.toString().startsWith("VALUE:"));
    }

    @Test
    void toString_exceptionPresent_startsWithHAS_ERROR() {
        ValueOrException<String, Exception> voe = ValueOrException.forException(new IOException("err"));
        assertTrue(voe.toString().startsWith("HAS ERROR"));
    }
}

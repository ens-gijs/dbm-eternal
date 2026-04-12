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
    void forValue_hasValueAndNoException() throws Exception {
        ValueOrException<String, Exception> voe = ValueOrException.forValue("hello");
        assertTrue(voe.hasValue());
        assertFalse(voe.hasException());
        assertEquals("hello", voe.getValue());
        assertNull(voe.getException());
        assertEquals("hello", voe.getOrThrow());
    }

    @Test
    void forValue_nullValueIsAllowed() throws Exception {
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
    void forException_getOrThrow_throwsWrappedException() {
        IOException ex = new IOException("test error");
        ValueOrException<String, IOException> voe = ValueOrException.forException(ex);
        IOException thrown = assertThrows(IOException.class, voe::getOrThrow);
        assertSame(ex, thrown);
    }

    // ---- eval ----

    @Test
    void eval_successCapturesToValue() throws Exception {
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
    void eval_withErrorCallback_doesNotCallCallbackOnSuccess() throws Exception {
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
    void empty_hasNeitherValueNorException() throws Exception {
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

package com.example.myapplication;

import com.example.utils.DateUtils;
import com.example.utils.KotlinHelper;
import com.example.utils.MathUtils;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ExampleUnitTest {
    @Test
    public void addition_isCorrect() {
        assertEquals(4, 2 + 2);
    }

    @Test
    public void mathUtilsArithmetic_isCorrect() {
        assertEquals(7, MathUtils.add(3, 4));
        assertEquals(-1, MathUtils.subtract(3, 4));
        assertEquals(12, MathUtils.multiply(3, 4));
        assertEquals(0.75, MathUtils.divide(3, 4), 0.000001);
    }

    @Test
    public void mathUtilsMaxMin_isCorrect() {
        assertEquals(9, MathUtils.max(3, 9, 4));
        assertEquals(3, MathUtils.min(3, 9, 4));
    }

    @Test(expected = IllegalArgumentException.class)
    public void mathUtilsDivide_byZero_throws() {
        MathUtils.divide(1, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void mathUtilsMax_empty_throws() {
        MathUtils.max();
    }

    @Test
    public void kotlinHelper_instanceAndStatic_areCorrect() {
        KotlinHelper helper = new KotlinHelper();
        assertEquals("Hello from Kotlin!", helper.getMessage());
        helper.setMessage("bye");
        assertEquals("bye", helper.getMessage());
        // @JvmOverloads: default argument is callable from Java with or without the parameter
        assertEquals("Hello, World!", helper.greet());
        assertEquals("Hello, Android!", helper.greet("Android"));
        assertEquals(6, helper.calculateSum(1, 2, 3));
        assertEquals("This is a static method from Kotlin!", KotlinHelper.staticMethod());
    }

    @Test
    public void dateUtils_format_isCorrect() {
        // 1970-01-01 in the host default time zone; assert the shape, not the shifted clock
        assertTrue(DateUtils.formatTime(0L).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
        assertTrue(DateUtils.getCurrentTime().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
        assertTrue(DateUtils.getCurrentTime("yyyy").matches("\\d{4}"));
        assertTrue(DateUtils.getTimestamp() > 0L);
    }
}
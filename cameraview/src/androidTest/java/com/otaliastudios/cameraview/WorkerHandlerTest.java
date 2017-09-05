package com.otaliastudios.cameraview;


import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class WorkerHandlerTest {

    @Test
    public void testCache() {
        WorkerHandler w1 = WorkerHandler.get("handler1");
        WorkerHandler w1a = WorkerHandler.get("handler1");
        WorkerHandler w2 = WorkerHandler.get("handler2");
        assertTrue(w1 == w1a);
        assertFalse(w1 == w2);
    }
}
